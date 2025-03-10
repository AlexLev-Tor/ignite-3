#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License. You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

project(ignite-client-test)

set(TARGET ${PROJECT_NAME})

set(SOURCES
    basic_authenticator_test.cpp
    compute_test.cpp
    gtest_logger.h
    ignite_client_test.cpp
    ignite_runner_suite.h
    key_value_binary_view_test.cpp
    key_value_view_test.cpp
    main.cpp
    record_binary_view_test.cpp
    record_view_test.cpp
    sql_test.cpp
    tables_test.cpp
    transactions_test.cpp
)

add_executable(${TARGET} ${SOURCES})
target_link_libraries(${TARGET} ignite-test-common ignite-client GTest::GTest)

set(TEST_TARGET IgniteClientTest)
add_test(NAME ${TEST_TARGET} COMMAND ${TARGET})
