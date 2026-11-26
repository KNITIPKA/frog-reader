cmake_minimum_required(VERSION 3.22.1)
project(frog_pdf_annotations LANGUAGES C)

add_library(frog_pdf_annotations SHARED pdf_annotations.c)
target_compile_features(frog_pdf_annotations PRIVATE c_std_11)
target_compile_options(frog_pdf_annotations PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(frog_pdf_annotations PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(frog_pdf_annotations PRIVATE dl m)
