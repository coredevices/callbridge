# The following lines of boilerplate have to be in your project's
# CMakeLists in this exact order for cmake to work correctly
cmake_minimum_required(VERSION 3.16)

# Bluedroid RFCOMM grants the phone new credits only when fewer than 4 of 10 remain. With both sides
# piggybacking credits on data, the link deadlocked for 20-40 s at a time (both directions ~1 frame/s).
# Hand credits back almost immediately instead: 30 outstanding, refill when 2 are used.
idf_build_set_property(COMPILE_DEFINITIONS "-DPORT_RX_BUF_HIGH_WM=30;-DPORT_RX_BUF_LOW_WM=28;-DPORT_TX_BUF_HIGH_WM=20" APPEND)

include($ENV{IDF_PATH}/tools/cmake/project.cmake)
project(callbridge)
