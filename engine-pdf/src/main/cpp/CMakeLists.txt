cmake_minimum_required(VERSION 3.22.1)
project(oopdf CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT DEFINED PDFIUM_ROOT)
    message(FATAL_ERROR "PDFIUM_ROOT not set; run scripts/fetch-pdfium.sh")
endif()
set(PDFIUM_DIR "${PDFIUM_ROOT}/${ANDROID_ABI}")
if(NOT EXISTS "${PDFIUM_DIR}/lib/libpdfium.so")
    message(FATAL_ERROR "Missing ${PDFIUM_DIR}/lib/libpdfium.so; run scripts/fetch-pdfium.sh")
endif()

add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES
    IMPORTED_LOCATION "${PDFIUM_DIR}/lib/libpdfium.so"
    INTERFACE_INCLUDE_DIRECTORIES "${PDFIUM_DIR}/include")

add_library(oopdf SHARED pdf_jni.cpp)
target_compile_options(oopdf PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(oopdf PRIVATE pdfium jnigraphics log)
target_link_options(oopdf PRIVATE "-Wl,-z,max-page-size=16384")
