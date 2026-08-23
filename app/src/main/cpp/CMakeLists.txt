cmake_minimum_required(VERSION 3.22.1)
project("usbreset")

add_library(usbreset SHARED
    usb_reset.c)

find_library(log-lib log)

target_link_libraries(usbreset ${log-lib})
