# SPDX-FileCopyrightText: 2015 - 2024 Rime community
# SPDX-License-Identifier: GPL-3.0-or-later

set(BOOST_INCLUDE_LIBRARIES
    algorithm
    crc
    dll
    interprocess
    preprocessor
    range
    regex
    scope_exit
    signals2
    utility
    uuid
    vmd)

add_subdirectory(boost EXCLUDE_FROM_ALL)
