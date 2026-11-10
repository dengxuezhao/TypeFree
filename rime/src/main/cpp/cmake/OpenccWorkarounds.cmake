# SPDX-FileCopyrightText: 2015 - 2024 Rime community
# SPDX-License-Identifier: GPL-3.0-or-later

file(GLOB LIBOPENCC_HEADERS librime/deps/opencc/src/*.hpp)
file(COPY ${LIBOPENCC_HEADERS} DESTINATION "${CMAKE_BINARY_DIR}/include/opencc")
