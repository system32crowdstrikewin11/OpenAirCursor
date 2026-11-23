cmake_minimum_required(VERSION 3.22.1)

project(openaircursor LANGUAGES CXX)

add_library(openaircursor SHARED openaircursor.cpp)

target_compile_features(openaircursor PRIVATE cxx_std_17)

find_library(log-lib log)
target_link_libraries(openaircursor ${log-lib})
