cmake_minimum_required(VERSION 3.22.1)

project(xomniclaw_aec)

add_library(
        xomniclaw_aec
        SHARED
        src/main/cpp/xomniclaw_aec_bridge.cpp)

find_library(log-lib log)

target_link_libraries(
        xomniclaw_aec
        ${log-lib})
