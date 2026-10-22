# postlab: the engine's post-processing as a command-line tool for the x86_64 emulator.
# Built and run by run.ps1; not part of the app.
cmake_minimum_required(VERSION 3.22.1)
project(postlab CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(ENGINE ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)

add_executable(postlab lab.cpp scene.cpp ${ENGINE}/post.cpp ${ENGINE}/post2.cpp)
target_include_directories(postlab PRIVATE ${ENGINE})
# Same flags as the app's engine, so the lab measures the code the phone runs.
target_compile_options(postlab PRIVATE -O3 -fno-math-errno -Wall)
target_link_libraries(postlab log)
