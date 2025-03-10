/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

namespace Apache.Ignite.Internal
{
    using System;
    using System.Buffers.Binary;
    using System.Collections.Concurrent;
    using System.Diagnostics;
    using System.Diagnostics.CodeAnalysis;
    using System.IO;
    using System.Linq;
    using System.Net;
    using System.Net.Security;
    using System.Net.Sockets;
    using System.Threading;
    using System.Threading.Tasks;
    using Buffers;
    using Ignite.Network;
    using Log;
    using Network;
    using Proto;
    using Proto.MsgPack;

    /// <summary>
    /// Wrapper over framework socket for Ignite thin client operations.
    /// </summary>
    // ReSharper disable SuggestBaseTypeForParameter (NetworkStream has more efficient read/write methods).
    internal sealed class ClientSocket : IDisposable
    {
        /** General-purpose client type code. */
        private const byte ClientType = 2;

        /** Version 3.0.0. */
        private static readonly ClientProtocolVersion Ver300 = new(3, 0, 0);

        /** Current version. */
        private static readonly ClientProtocolVersion CurrentProtocolVersion = Ver300;

        /** Minimum supported heartbeat interval. */
        private static readonly TimeSpan MinRecommendedHeartbeatInterval = TimeSpan.FromMilliseconds(500);

        /** Socket id for debug logging. */
        private static long _socketId;

        /** Underlying stream. */
        private readonly Stream _stream;

        /** Current async operations, map from request id. */
        private readonly ConcurrentDictionary<long, TaskCompletionSource<PooledBuffer>> _requests = new();

        /** Requests can be sent by one thread at a time.  */
        [SuppressMessage(
            "Microsoft.Design",
            "CA2213:DisposableFieldsShouldBeDisposed",
            Justification = "WaitHandle is not used in SemaphoreSlim, no need to dispose.")]
        private readonly SemaphoreSlim _sendLock = new(initialCount: 1);

        /** Cancellation token source that gets cancelled when this instance is disposed. */
        [SuppressMessage(
            "Microsoft.Design",
            "CA2213:DisposableFieldsShouldBeDisposed",
            Justification = "WaitHandle is not used in CancellationTokenSource, no need to dispose.")]
        private readonly CancellationTokenSource _disposeTokenSource = new();

        /** Dispose lock. */
        private readonly object _disposeLock = new();

        /** Heartbeat timer. */
        private readonly Timer _heartbeatTimer;

        /** Effective heartbeat interval. */
        private readonly TimeSpan _heartbeatInterval;

        /** Socket timeout for handshakes and heartbeats. */
        private readonly TimeSpan _socketTimeout;

        /** Logger. */
        private readonly IIgniteLogger? _logger;

        /** Partition assignment change callback. */
        private readonly Action<ClientSocket> _assignmentChangeCallback;

        /** Pre-allocated buffer for message size + op code + request id. To be used under <see cref="_sendLock"/>. */
        private readonly byte[] _prefixBuffer = new byte[ProtoCommon.MessagePrefixSize];

        /** Request id generator. */
        private long _requestId;

        /** Exception that caused this socket to close. */
        private volatile Exception? _exception;

        /// <summary>
        /// Initializes a new instance of the <see cref="ClientSocket"/> class.
        /// </summary>
        /// <param name="stream">Network stream.</param>
        /// <param name="configuration">Configuration.</param>
        /// <param name="connectionContext">Connection context.</param>
        /// <param name="assignmentChangeCallback">Partition assignment change callback.</param>
        /// <param name="logger">Logger.</param>
        private ClientSocket(
            Stream stream,
            IgniteClientConfiguration configuration,
            ConnectionContext connectionContext,
            Action<ClientSocket> assignmentChangeCallback,
            IIgniteLogger? logger)
        {
            _stream = stream;
            ConnectionContext = connectionContext;
            _assignmentChangeCallback = assignmentChangeCallback;
            _logger = logger;
            _socketTimeout = configuration.SocketTimeout;

            _heartbeatInterval = GetHeartbeatInterval(configuration.HeartbeatInterval, connectionContext.IdleTimeout, _logger);

            // ReSharper disable once AsyncVoidLambda (timer callback)
            _heartbeatTimer = new Timer(
                callback: async _ => await SendHeartbeatAsync().ConfigureAwait(false),
                state: null,
                dueTime: _heartbeatInterval,
                period: TimeSpan.FromMilliseconds(-1));

            // Because this call is not awaited, execution of the current method continues before the call is completed.
            // Receive loop runs in the background and should not be awaited.
            _ = RunReceiveLoop(_disposeTokenSource.Token);
        }

        /// <summary>
        /// Gets a value indicating whether this socket is disposed.
        /// </summary>
        public bool IsDisposed => _disposeTokenSource.IsCancellationRequested;

        /// <summary>
        /// Gets the connection context.
        /// </summary>
        public ConnectionContext ConnectionContext { get; }

        /// <summary>
        /// Connects the socket to the specified endpoint and performs handshake.
        /// </summary>
        /// <param name="endPoint">Specific endpoint to connect to.</param>
        /// <param name="configuration">Configuration.</param>
        /// <param name="assignmentChangeCallback">Partition assignment change callback.</param>
        /// <returns>A <see cref="Task{TResult}"/> representing the result of the asynchronous operation.</returns>
        [SuppressMessage(
            "Microsoft.Reliability",
            "CA2000:Dispose objects before losing scope",
            Justification = "NetworkStream is returned from this method in the socket.")]
        public static async Task<ClientSocket> ConnectAsync(
            SocketEndpoint endPoint,
            IgniteClientConfiguration configuration,
            Action<ClientSocket> assignmentChangeCallback)
        {
            var socket = new Socket(SocketType.Stream, ProtocolType.Tcp)
            {
                NoDelay = true
            };

            var logger = configuration.Logger.GetLogger(nameof(ClientSocket) + "-" + Interlocked.Increment(ref _socketId));
            bool connected = false;

            try
            {
                await socket.ConnectAsync(endPoint.EndPoint).ConfigureAwait(false);
                connected = true;

                if (logger?.IsEnabled(LogLevel.Debug) == true)
                {
                    logger.Debug($"Connection established [remoteAddress={socket.RemoteEndPoint}]");
                }

                Metrics.ConnectionsEstablished.Add(1);
                Metrics.ConnectionsActiveIncrement();

                Stream stream = new NetworkStream(socket, ownsSocket: true);

                if (configuration.SslStreamFactory is { } sslStreamFactory &&
                    await sslStreamFactory.CreateAsync(stream, endPoint.Host).ConfigureAwait(false) is { } sslStream)
                {
                    stream = sslStream;

                    if (logger?.IsEnabled(LogLevel.Debug) == true)
                    {
                        logger.Debug(
                            $"SSL connection established [remoteAddress={socket.RemoteEndPoint}]: {sslStream.NegotiatedCipherSuite}");
                    }
                }

                var context = await HandshakeAsync(stream, endPoint.EndPoint, configuration)
                    .WaitAsync(configuration.SocketTimeout)
                    .ConfigureAwait(false);

                if (logger?.IsEnabled(LogLevel.Debug) == true)
                {
                    logger.Debug($"Handshake succeeded [remoteAddress={socket.RemoteEndPoint}]: {context}.");
                }

                return new ClientSocket(stream, configuration, context, assignmentChangeCallback, logger);
            }
            catch (Exception e)
            {
                logger?.Warn($"Connection failed before or during handshake [remoteAddress={endPoint.EndPoint}]: {e.Message}.", e);

                if (e.GetBaseException() is TimeoutException)
                {
                    Metrics.HandshakesFailedTimeout.Add(1);
                }
                else
                {
                    Metrics.HandshakesFailed.Add(1);
                }

                // ReSharper disable once MethodHasAsyncOverload
                socket.Dispose();

                if (connected)
                {
                    Metrics.ConnectionsActiveDecrement();
                }

                throw new IgniteClientConnectionException(
                    ErrorGroups.Client.Connection,
                    "Failed to connect to endpoint: " + endPoint.EndPoint,
                    e);
            }
        }

        /// <summary>
        /// Performs an in-out operation.
        /// </summary>
        /// <param name="clientOp">Client op code.</param>
        /// <param name="request">Request data.</param>
        /// <returns>Response data.</returns>
        public Task<PooledBuffer> DoOutInOpAsync(ClientOp clientOp, PooledArrayBuffer? request = null)
        {
            var ex = _exception;

            if (ex != null)
            {
                throw new IgniteClientConnectionException(
                    ErrorGroups.Client.Connection,
                    "Socket is closed due to an error, examine inner exception for details.",
                    ex);
            }

            if (_disposeTokenSource.IsCancellationRequested)
            {
                throw new IgniteClientConnectionException(
                    ErrorGroups.Client.Connection,
                    "Socket is disposed.",
                    new ObjectDisposedException(nameof(ClientSocket)));
            }

            var requestId = Interlocked.Increment(ref _requestId);
            var taskCompletionSource = new TaskCompletionSource<PooledBuffer>();
            _requests[requestId] = taskCompletionSource;

            Metrics.RequestsActiveIncrement();

            SendRequestAsync(request, clientOp, requestId)
                .AsTask()
                .ContinueWith(
                    (task, state) =>
                    {
                        var completionSource = (TaskCompletionSource<PooledBuffer>)state!;

                        if (task.IsCanceled || task.Exception?.GetBaseException() is OperationCanceledException or ObjectDisposedException)
                        {
                            // Canceled task means Dispose was called.
                            completionSource.TrySetException(
                                new IgniteClientConnectionException(ErrorGroups.Client.Connection, "Connection closed."));
                        }
                        else if (task.Exception != null)
                        {
                            completionSource.TrySetException(task.Exception);
                        }

                        if (_requests.TryRemove(requestId, out _))
                        {
                            Metrics.RequestsFailed.Add(1);
                            Metrics.RequestsActiveDecrement();
                        }
                    },
                    taskCompletionSource,
                    CancellationToken.None,
                    TaskContinuationOptions.NotOnRanToCompletion,
                    TaskScheduler.Default);

            return taskCompletionSource.Task;
        }

        /// <inheritdoc/>
        public void Dispose()
        {
            Dispose(null);
        }

        /// <summary>
        /// Performs the handshake exchange.
        /// </summary>
        /// <param name="stream">Network stream.</param>
        /// <param name="endPoint">Endpoint.</param>
        /// <param name="configuration">Configuration.</param>
        private static async Task<ConnectionContext> HandshakeAsync(
            Stream stream,
            IPEndPoint endPoint,
            IgniteClientConfiguration configuration)
        {
            await stream.WriteAsync(ProtoCommon.MagicBytes).ConfigureAwait(false);
            await WriteHandshakeAsync(stream, CurrentProtocolVersion, configuration).ConfigureAwait(false);

            await stream.FlushAsync().ConfigureAwait(false);

            await CheckMagicBytesAsync(stream).ConfigureAwait(false);

            using var response = await ReadResponseAsync(stream, new byte[4], CancellationToken.None).ConfigureAwait(false);
            return ReadHandshakeResponse(response.GetReader(), endPoint, GetSslInfo(stream));
        }

        private static async ValueTask CheckMagicBytesAsync(Stream stream)
        {
            var responseMagic = ByteArrayPool.Rent(ProtoCommon.MagicBytes.Length);

            try
            {
                await ReceiveBytesAsync(stream, responseMagic, ProtoCommon.MagicBytes.Length, CancellationToken.None).ConfigureAwait(false);

                for (var i = 0; i < ProtoCommon.MagicBytes.Length; i++)
                {
                    if (responseMagic[i] != ProtoCommon.MagicBytes[i])
                    {
                        throw new IgniteClientConnectionException(
                            ErrorGroups.Client.Protocol,
                            "Invalid magic bytes returned from the server: " + BitConverter.ToString(responseMagic));
                    }
                }
            }
            finally
            {
                ByteArrayPool.Return(responseMagic);
            }
        }

        private static ConnectionContext ReadHandshakeResponse(MsgPackReader reader, IPEndPoint endPoint, ISslInfo? sslInfo)
        {
            var serverVer = new ClientProtocolVersion(reader.ReadInt16(), reader.ReadInt16(), reader.ReadInt16());

            if (serverVer != CurrentProtocolVersion)
            {
                throw new IgniteClientConnectionException(ErrorGroups.Client.Protocol, "Unexpected server version: " + serverVer);
            }

            var exception = ReadError(ref reader);

            if (exception != null)
            {
                throw exception;
            }

            var idleTimeoutMs = reader.ReadInt64();
            var clusterNodeId = reader.ReadString();
            var clusterNodeName = reader.ReadString();
            var clusterId = reader.ReadGuid();

            reader.Skip(); // Features.
            reader.Skip(); // Extensions.

            return new ConnectionContext(
                serverVer,
                TimeSpan.FromMilliseconds(idleTimeoutMs),
                new ClusterNode(clusterNodeId, clusterNodeName, endPoint),
                clusterId,
                sslInfo);
        }

        private static IgniteException? ReadError(ref MsgPackReader reader)
        {
            if (reader.TryReadNil())
            {
                return null;
            }

            Guid traceId = reader.TryReadNil() ? Guid.NewGuid() : reader.ReadGuid();
            int code = reader.TryReadNil() ? 65537 : reader.ReadInt32();
            string className = reader.ReadString();
            string? message = reader.ReadStringNullable();
            string? javaStackTrace = reader.ReadStringNullable();

            // TODO IGNITE-19838 Retry outdated schema error
            reader.Skip(); // Error extensions.

            return ExceptionMapper.GetException(traceId, code, className, message, javaStackTrace);
        }

        private static async ValueTask<PooledBuffer> ReadResponseAsync(
            Stream stream,
            byte[] messageSizeBytes,
            CancellationToken cancellationToken)
        {
            var size = await ReadMessageSizeAsync(stream, messageSizeBytes, cancellationToken).ConfigureAwait(false);

            var bytes = ByteArrayPool.Rent(size);

            try
            {
                await ReceiveBytesAsync(stream, bytes, size, cancellationToken).ConfigureAwait(false);

                return new PooledBuffer(bytes, 0, size);
            }
            catch (Exception)
            {
                ByteArrayPool.Return(bytes);

                throw;
            }
        }

        private static async Task<int> ReadMessageSizeAsync(
            Stream stream,
            byte[] buffer,
            CancellationToken cancellationToken)
        {
            const int messageSizeByteCount = 4;
            Debug.Assert(buffer.Length >= messageSizeByteCount, "buffer.Length >= messageSizeByteCount");

            await ReceiveBytesAsync(stream, buffer, messageSizeByteCount, cancellationToken).ConfigureAwait(false);

            return ReadMessageSize(buffer);
        }

        private static async Task ReceiveBytesAsync(
            Stream stream,
            byte[] buffer,
            int size,
            CancellationToken cancellationToken)
        {
            int received = 0;

            while (received < size)
            {
                var res = await stream.ReadAsync(buffer.AsMemory(received, size - received), cancellationToken).ConfigureAwait(false);

                if (res == 0)
                {
                    // Disconnected.
                    throw new IgniteClientConnectionException(
                        ErrorGroups.Client.Protocol,
                        "Connection lost (failed to read data from socket)",
                        new SocketException((int) SocketError.ConnectionAborted));
                }

                received += res;

                Metrics.BytesReceived.Add(res);
            }
        }

        private static async ValueTask WriteHandshakeAsync(
            Stream stream,
            ClientProtocolVersion version,
            IgniteClientConfiguration configuration)
        {
            using var bufferWriter = new PooledArrayBuffer(prefixSize: ProtoCommon.MessagePrefixSize);
            WriteHandshake(bufferWriter.MessageWriter, version, configuration);

            // Prepend size.
            var buf = bufferWriter.GetWrittenMemory();
            var size = buf.Length - ProtoCommon.MessagePrefixSize;
            var resBuf = buf.Slice(ProtoCommon.MessagePrefixSize - 4);
            WriteMessageSize(resBuf, size);

            await stream.WriteAsync(resBuf).ConfigureAwait(false);
            Metrics.BytesSent.Add(resBuf.Length);
        }

        private static void WriteHandshake(MsgPackWriter w, ClientProtocolVersion version, IgniteClientConfiguration configuration)
        {
            // Version.
            w.Write(version.Major);
            w.Write(version.Minor);
            w.Write(version.Patch);

            w.Write(ClientType); // Client type: general purpose.

            w.WriteBinaryHeader(0); // Features.

            if (configuration.Authenticator != null)
            {
                w.WriteMapHeader(3); // Extensions.

                w.Write(HandshakeExtensions.AuthenticationType);
                w.Write(configuration.Authenticator.Type);

                w.Write(HandshakeExtensions.AuthenticationIdentity);
                w.Write((string?)configuration.Authenticator.Identity);

                w.Write(HandshakeExtensions.AuthenticationSecret);
                w.Write((string?)configuration.Authenticator.Secret);
            }
            else
            {
                w.WriteMapHeader(0); // Extensions.
            }
        }

        private static void WriteMessageSize(Memory<byte> target, int size) =>
            BinaryPrimitives.WriteInt32BigEndian(target.Span, size);

        private static int ReadMessageSize(Span<byte> responseLenBytes) => BinaryPrimitives.ReadInt32BigEndian(responseLenBytes);

        private static TimeSpan GetHeartbeatInterval(TimeSpan configuredInterval, TimeSpan serverIdleTimeout, IIgniteLogger? logger)
        {
            if (configuredInterval <= TimeSpan.Zero)
            {
                throw new IgniteClientException(
                    ErrorGroups.Client.Configuration,
                    $"{nameof(IgniteClientConfiguration)}.{nameof(IgniteClientConfiguration.HeartbeatInterval)} should be greater than zero.");
            }

            if (serverIdleTimeout <= TimeSpan.Zero)
            {
                logger?.Info(
                    $"Server-side IdleTimeout is not set, using configured {nameof(IgniteClientConfiguration)}." +
                    $"{nameof(IgniteClientConfiguration.HeartbeatInterval)}: {configuredInterval}");

                return configuredInterval;
            }

            var recommendedHeartbeatInterval = serverIdleTimeout / 3;

            if (recommendedHeartbeatInterval < MinRecommendedHeartbeatInterval)
            {
                recommendedHeartbeatInterval = MinRecommendedHeartbeatInterval;
            }

            if (configuredInterval < recommendedHeartbeatInterval)
            {
                logger?.Info(
                    $"Server-side IdleTimeout is {serverIdleTimeout}, " +
                    $"using configured {nameof(IgniteClientConfiguration)}." +
                    $"{nameof(IgniteClientConfiguration.HeartbeatInterval)}: " +
                    configuredInterval);

                return configuredInterval;
            }

            logger?.Warn(
                $"Server-side IdleTimeout is {serverIdleTimeout}, configured " +
                $"{nameof(IgniteClientConfiguration)}.{nameof(IgniteClientConfiguration.HeartbeatInterval)} " +
                $"is {configuredInterval}, which is longer than recommended IdleTimeout / 3. " +
                $"Overriding heartbeat interval with max(IdleTimeout / 3, 500ms): {recommendedHeartbeatInterval}");

            return recommendedHeartbeatInterval;
        }

        private static ISslInfo? GetSslInfo(Stream stream) =>
            stream is SslStream sslStream
                ? new SslInfo(
                    sslStream.TargetHostName,
                    sslStream.NegotiatedCipherSuite.ToString(),
                    sslStream.IsMutuallyAuthenticated,
                    sslStream.LocalCertificate,
                    sslStream.RemoteCertificate,
                    sslStream.SslProtocol)
                : null;

        private async ValueTask SendRequestAsync(PooledArrayBuffer? request, ClientOp op, long requestId)
        {
            // Reset heartbeat timer - don't sent heartbeats when connection is active anyway.
            _heartbeatTimer.Change(dueTime: _heartbeatInterval, period: TimeSpan.FromMilliseconds(-1));

            if (_logger?.IsEnabled(LogLevel.Trace) == true)
            {
                _logger.Trace($"Sending request [op={op}, remoteAddress={ConnectionContext.ClusterNode.Address}, requestId={requestId}]");
            }

            await _sendLock.WaitAsync(_disposeTokenSource.Token).ConfigureAwait(false);

            try
            {
                var prefixMem = _prefixBuffer.AsMemory()[4..];
                var prefixSize = MsgPackWriter.WriteUnsigned(prefixMem.Span, (ulong)op);
                prefixSize += MsgPackWriter.WriteUnsigned(prefixMem[prefixSize..].Span, (ulong)requestId);

                if (request != null)
                {
                    var requestBuf = request.GetWrittenMemory();

                    WriteMessageSize(_prefixBuffer, prefixSize + requestBuf.Length - ProtoCommon.MessagePrefixSize);
                    var prefixBytes = _prefixBuffer.AsMemory()[..(prefixSize + 4)];

                    var requestBufStart = ProtoCommon.MessagePrefixSize - prefixBytes.Length;
                    var requestBufWithPrefix = requestBuf.Slice(requestBufStart);

                    // Copy prefix to request buf to avoid extra WriteAsync call for the prefix.
                    prefixBytes.CopyTo(requestBufWithPrefix);

                    await _stream.WriteAsync(requestBufWithPrefix, _disposeTokenSource.Token).ConfigureAwait(false);

                    Metrics.BytesSent.Add(requestBufWithPrefix.Length);
                }
                else
                {
                    // Request without body, send only the prefix.
                    WriteMessageSize(_prefixBuffer, prefixSize);
                    var prefixBytes = _prefixBuffer.AsMemory()[..(prefixSize + 4)];
                    await _stream.WriteAsync(prefixBytes, _disposeTokenSource.Token).ConfigureAwait(false);

                    Metrics.BytesSent.Add(prefixBytes.Length);
                }

                Metrics.RequestsSent.Add(1);
            }
            finally
            {
                _sendLock.Release();
            }
        }

        [SuppressMessage(
            "Microsoft.Design",
            "CA1031:DoNotCatchGeneralExceptionTypes",
            Justification = "Any exception in receive loop should be handled.")]
        private async Task RunReceiveLoop(CancellationToken cancellationToken)
        {
            // Reuse the same array for all responses.
            var messageSizeBytes = new byte[4];

            try
            {
                while (!cancellationToken.IsCancellationRequested)
                {
                    PooledBuffer response = await ReadResponseAsync(_stream, messageSizeBytes, cancellationToken).ConfigureAwait(false);

                    // Invoke response handler in another thread to continue the receive loop.
                    // Response buffer should be disposed by the task handler.
                    ThreadPool.QueueUserWorkItem(r => HandleResponse((PooledBuffer)r!), response);
                }
            }
            catch (Exception e)
            {
                var message = "Exception while reading from socket, connection closed: " + e.Message;

                _logger?.Error(e, message);
                Dispose(new IgniteClientConnectionException(ErrorGroups.Client.Connection, message, e));
            }
        }

        private void HandleResponse(PooledBuffer response)
        {
            var reader = response.GetReader();

            var responseType = (ServerMessageType)reader.ReadInt32();

            if (responseType != ServerMessageType.Response)
            {
                // Notifications are not used for now.
                return;
            }

            var requestId = reader.ReadInt64();

            if (!_requests.TryRemove(requestId, out var taskCompletionSource))
            {
                var message = $"Unexpected response ID ({requestId}) received from the server " +
                              $"[remoteAddress={ConnectionContext.ClusterNode.Address}], closing the socket.";

                _logger?.Error(message);
                Dispose(new IgniteClientConnectionException(ErrorGroups.Client.Protocol, message));

                return;
            }

            Metrics.RequestsActiveDecrement();

            var flags = (ResponseFlags)reader.ReadInt32();

            if (flags.HasFlag(ResponseFlags.PartitionAssignmentChanged))
            {
                if (_logger?.IsEnabled(LogLevel.Info) == true)
                {
                    _logger.Info(
                        $"Partition assignment change notification received [remoteAddress={ConnectionContext.ClusterNode.Address}]");
                }

                _assignmentChangeCallback(this);
            }

            var exception = ReadError(ref reader);

            if (exception != null)
            {
                response.Dispose();

                Metrics.RequestsFailed.Add(1);

                taskCompletionSource.SetException(exception);
            }
            else
            {
                var resultBuffer = response.Slice(reader.Consumed);

                Metrics.RequestsCompleted.Add(1);

                taskCompletionSource.SetResult(resultBuffer);
            }
        }

        /// <summary>
        /// Sends heartbeat message.
        /// </summary>
        [SuppressMessage(
            "Microsoft.Design",
            "CA1031:DoNotCatchGeneralExceptionTypes",
            Justification = "Any heartbeat exception should cause this instance to be disposed with an error.")]
        private async Task SendHeartbeatAsync()
        {
            try
            {
                await DoOutInOpAsync(ClientOp.Heartbeat).WaitAsync(_socketTimeout).ConfigureAwait(false);
            }
            catch (Exception e)
            {
                _logger?.Error(e, "Heartbeat failed: " + e.Message);

                Dispose(e);
            }
        }

        /// <summary>
        /// Disposes this socket and completes active requests with the specified exception.
        /// </summary>
        /// <param name="ex">Exception that caused this socket to close. Null when socket is closed by the user.</param>
        private void Dispose(Exception? ex)
        {
            lock (_disposeLock)
            {
                if (_disposeTokenSource.IsCancellationRequested)
                {
                    return;
                }

                _disposeTokenSource.Cancel();

                if (ex != null)
                {
                    _logger?.Warn(ex, $"Connection closed [remoteAddress={ConnectionContext.ClusterNode.Address}]: " + ex.Message);

                    if (ex.GetBaseException() is TimeoutException)
                    {
                        Metrics.ConnectionsLostTimeout.Add(1);
                    }
                    else
                    {
                        Metrics.ConnectionsLost.Add(1);
                    }
                }
                else if (_logger?.IsEnabled(LogLevel.Debug) == true)
                {
                    _logger.Debug($"Connection closed [remoteAddress={ConnectionContext.ClusterNode.Address}]");
                }

                _heartbeatTimer.Dispose();
                _exception = ex;
                _stream.Dispose();

                ex ??= new IgniteClientConnectionException(ErrorGroups.Client.Connection, "Connection closed.");

                while (!_requests.IsEmpty)
                {
                    foreach (var reqId in _requests.Keys.ToArray())
                    {
                        if (_requests.TryRemove(reqId, out var req))
                        {
                            req.TrySetException(ex);
                            Metrics.RequestsActiveDecrement();
                        }
                    }
                }

                Metrics.ConnectionsActiveDecrement();
            }
        }
    }
}
