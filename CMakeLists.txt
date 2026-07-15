cmake_minimum_required(VERSION 3.16)

project(fpt_reference VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(FPT_BUILD_TESTS "Build the standalone fixed-point FFT tests" ON)
option(FPT_BUILD_TFHEPP_TESTS "Build the TFHEpp Blind Rotate integration test" OFF)
option(FPT_BUILD_RTL_TESTS "Build C++ vectors and test the RTL arithmetic" ON)
set(FPT_TFHEPP_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../TFHEpp" CACHE PATH
    "Path to a TFHEpp source checkout")

add_library(fpt_reference
    src/fft.cpp
)
add_library(FPT::reference ALIAS fpt_reference)

target_include_directories(fpt_reference
    PUBLIC
        $<BUILD_INTERFACE:${CMAKE_CURRENT_SOURCE_DIR}/include>
        $<INSTALL_INTERFACE:include>
)
target_compile_features(fpt_reference PUBLIC cxx_std_20)
target_compile_options(fpt_reference PRIVATE -Wall -Wextra)

if(FPT_BUILD_TESTS)
    include(CTest)
    add_executable(fpt_fft_test tests/fft_test.cpp)
    target_link_libraries(fpt_fft_test PRIVATE FPT::reference)
    target_compile_options(fpt_fft_test PRIVATE -Wall -Wextra)
    add_test(NAME fpt_fft_test COMMAND fpt_fft_test)
endif()

if(FPT_BUILD_RTL_TESTS)
    find_program(FPT_IVERILOG_EXECUTABLE iverilog)
    if(FPT_IVERILOG_EXECUTABLE)
        add_executable(fpt_rtl_vector_gen tests/rtl_vector_gen.cpp)
        target_link_libraries(fpt_rtl_vector_gen PRIVATE FPT::reference)
        target_compile_options(fpt_rtl_vector_gen PRIVATE -Wall -Wextra)
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/rtl_vectors.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_mac_vectors.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_fft_vectors.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_fft_twiddles.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_tangent_vectors.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_tangent_twiddles.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_ifft_vectors.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_ifft_twiddles.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_untwist_twiddles.txt"
            COMMAND fpt_rtl_vector_gen
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_mac_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_fft_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_fft_twiddles.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_tangent_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_tangent_twiddles.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_ifft_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_ifft_twiddles.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_untwist_twiddles.txt"
            DEPENDS fpt_rtl_vector_gen
            VERBATIM
        )
        add_custom_target(fpt_rtl_vectors ALL
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/rtl_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_mac_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_fft_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_fft_twiddles.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_tangent_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_tangent_twiddles.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_ifft_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_ifft_twiddles.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_untwist_twiddles.txt")
        add_test(NAME fpt_rtl_arithmetic
            COMMAND bash "${CMAKE_CURRENT_SOURCE_DIR}/tests/run_rtl_test.sh"
                    "${FPT_IVERILOG_EXECUTABLE}"
                    "${CMAKE_CURRENT_SOURCE_DIR}"
                    "${CMAKE_CURRENT_BINARY_DIR}")
    else()
        message(WARNING "iverilog not found; skipping RTL simulation test")
    endif()
endif()

if(FPT_BUILD_TFHEPP_TESTS)
    if(NOT EXISTS "${FPT_TFHEPP_SOURCE_DIR}/include/params.hpp")
        message(FATAL_ERROR "FPT_TFHEPP_SOURCE_DIR does not contain TFHEpp")
    endif()

    add_executable(fpt_tfhepp_blind_rotate_test
        tests/tfhepp_blind_rotate_test.cpp
    )
    target_include_directories(fpt_tfhepp_blind_rotate_test PRIVATE
        "${FPT_TFHEPP_SOURCE_DIR}/include"
        "${FPT_TFHEPP_SOURCE_DIR}/thirdparties/spqlios"
        "${FPT_TFHEPP_SOURCE_DIR}/thirdparties/cereal/include"
        "${FPT_TFHEPP_SOURCE_DIR}/thirdparties/HLS_arbitrary_Precision_Types/include"
    )
    target_link_libraries(fpt_tfhepp_blind_rotate_test PRIVATE
        FPT::reference
    )
    target_compile_options(fpt_tfhepp_blind_rotate_test PRIVATE
        -Wall -Wextra -Wno-sign-compare -Wno-unknown-pragmas
    )
    add_test(NAME fpt_tfhepp_blind_rotate_test
             COMMAND fpt_tfhepp_blind_rotate_test)
endif()
