cmake_minimum_required(VERSION 3.16)

project(fpt_reference VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(FPT_BUILD_TESTS "Build the standalone fixed-point FFT tests" ON)
option(FPT_BUILD_TFHEPP_TESTS "Build the TFHEpp Blind Rotate integration test" OFF)
option(FPT_BUILD_RTL_TESTS "Build C++ vectors for the Chisel RTL tests" ON)
set(FPT_TFHEPP_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../TFHEpp" CACHE PATH
    "Path to a TFHEpp source checkout")

include(CTest)

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
    add_executable(fpt_fft_test tests/fft_test.cpp)
    target_link_libraries(fpt_fft_test PRIVATE FPT::reference)
    target_compile_options(fpt_fft_test PRIVATE -Wall -Wextra)
    add_test(NAME fpt_fft_test COMMAND fpt_fft_test)
endif()

if(FPT_BUILD_RTL_TESTS)
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
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_sgen_fft_vectors.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_external_product_vectors.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_cmux_frontend_vectors.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_cmux_engine_vectors.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_cmux_engine_twiddles.txt"
                   "${CMAKE_CURRENT_BINARY_DIR}/rtl_blind_rotate_vectors.txt"
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
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_sgen_fft_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_external_product_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_cmux_frontend_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_cmux_engine_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_cmux_engine_twiddles.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_blind_rotate_vectors.txt"
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
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_untwist_twiddles.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_sgen_fft_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_external_product_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_cmux_frontend_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_cmux_engine_vectors.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_cmux_engine_twiddles.txt"
                    "${CMAKE_CURRENT_BINARY_DIR}/rtl_blind_rotate_vectors.txt")

        add_executable(fpt_paper_sgen_vector_gen
            tests/paper_sgen_vector_gen.cpp)
        target_link_libraries(fpt_paper_sgen_vector_gen PRIVATE FPT::reference)
        target_compile_options(fpt_paper_sgen_vector_gen PRIVATE -Wall -Wextra)
        add_custom_command(
            OUTPUT
                "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_sgen_forward_vectors.txt"
                "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_sgen_inverse_vectors.txt"
            COMMAND fpt_paper_sgen_vector_gen
                "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_sgen_forward_vectors.txt"
                "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_sgen_inverse_vectors.txt"
            DEPENDS fpt_paper_sgen_vector_gen
            VERBATIM
        )
        add_custom_target(fpt_paper_sgen_vectors
            DEPENDS
                "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_sgen_forward_vectors.txt"
                "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_sgen_inverse_vectors.txt"
        )

        add_executable(fpt_paper_cmux_vector_gen
            tests/paper_cmux_vector_gen.cpp)
        target_link_libraries(fpt_paper_cmux_vector_gen PRIVATE FPT::reference)
        target_compile_options(fpt_paper_cmux_vector_gen PRIVATE -Wall -Wextra)
        add_custom_command(
            OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_cmux_vectors.txt"
            COMMAND fpt_paper_cmux_vector_gen
                "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_cmux_vectors.txt"
            DEPENDS fpt_paper_cmux_vector_gen
            VERBATIM
        )
        add_custom_target(fpt_paper_cmux_vectors
            DEPENDS "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_cmux_vectors.txt"
        )

        add_executable(fpt_paper_blind_rotate_vector_gen
            tests/paper_blind_rotate_vector_gen.cpp)
        target_link_libraries(fpt_paper_blind_rotate_vector_gen
            PRIVATE FPT::reference)
        target_compile_options(fpt_paper_blind_rotate_vector_gen
            PRIVATE -Wall -Wextra)
        add_custom_command(
            OUTPUT
                "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_blind_rotate_vectors.txt"
            COMMAND fpt_paper_blind_rotate_vector_gen
                "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_blind_rotate_vectors.txt"
            DEPENDS fpt_paper_blind_rotate_vector_gen
            VERBATIM
        )
        add_custom_target(fpt_paper_blind_rotate_vectors
            DEPENDS
                "${CMAKE_CURRENT_BINARY_DIR}/rtl_paper_blind_rotate_vectors.txt"
        )
        add_test(NAME fpt_rtl_source_policy
            COMMAND bash
                "${CMAKE_CURRENT_SOURCE_DIR}/tools/check_rtl_source_policy.sh")
        add_test(NAME fpt_prepared_u280_handoff
            COMMAND bash
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/prepared_u280_handoff_test.sh")
        add_test(NAME fpt_buffered_blind_rotate_boundary
            COMMAND bash
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/fpt_buffered_blind_rotate_boundary_test.sh")
        add_test(NAME fpt_hoge_blind_rotate_boundary
            COMMAND bash
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/hoge_blind_rotate_boundary_test.sh")
        add_test(NAME fpt_yosys_blind_rotate_report
            COMMAND bash
                "${CMAKE_CURRENT_SOURCE_DIR}/tests/yosys_blind_rotate_report_test.sh")
endif()

if(FPT_BUILD_TFHEPP_TESTS)
    if(NOT EXISTS "${FPT_TFHEPP_SOURCE_DIR}/include/params.hpp")
        message(FATAL_ERROR "FPT_TFHEPP_SOURCE_DIR does not contain TFHEpp")
    endif()

    add_executable(fpt_tfhepp_blind_rotate_test
        tests/tfhepp_blind_rotate_test.cpp
    )
    add_executable(fpt_tfhepp_profile_sweep
        tests/tfhepp_profile_sweep.cpp
    )
    add_executable(fpt_tfhepp_rtl_profile_stress
        tests/tfhepp_rtl_profile_stress.cpp
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
    target_include_directories(fpt_tfhepp_profile_sweep PRIVATE
        "${FPT_TFHEPP_SOURCE_DIR}/include"
        "${FPT_TFHEPP_SOURCE_DIR}/thirdparties/spqlios"
        "${FPT_TFHEPP_SOURCE_DIR}/thirdparties/cereal/include"
        "${FPT_TFHEPP_SOURCE_DIR}/thirdparties/HLS_arbitrary_Precision_Types/include"
    )
    target_link_libraries(fpt_tfhepp_profile_sweep PRIVATE
        FPT::reference
    )
    target_include_directories(fpt_tfhepp_rtl_profile_stress PRIVATE
        "${FPT_TFHEPP_SOURCE_DIR}/include"
        "${FPT_TFHEPP_SOURCE_DIR}/thirdparties/spqlios"
        "${FPT_TFHEPP_SOURCE_DIR}/thirdparties/cereal/include"
        "${FPT_TFHEPP_SOURCE_DIR}/thirdparties/HLS_arbitrary_Precision_Types/include"
    )
    target_link_libraries(fpt_tfhepp_rtl_profile_stress PRIVATE
        FPT::reference
    )
    target_compile_options(fpt_tfhepp_blind_rotate_test PRIVATE
        -Wall -Wextra -Wno-sign-compare -Wno-unknown-pragmas
    )
    target_compile_options(fpt_tfhepp_profile_sweep PRIVATE
        -Wall -Wextra -Wno-sign-compare -Wno-unknown-pragmas
    )
    target_compile_options(fpt_tfhepp_rtl_profile_stress PRIVATE
        -Wall -Wextra -Wno-sign-compare -Wno-unknown-pragmas
    )
    # The profile sweep and randomized RTL-profile stress are opt-in,
    # multi-minute diagnostics.
    add_test(NAME fpt_tfhepp_blind_rotate_test
             COMMAND fpt_tfhepp_blind_rotate_test)
endif()
