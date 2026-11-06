# Third-party notices

Simple 3DOF TV includes or builds on the software below. Each part keeps its own licence.

## VITURE glasses SDK (proprietary)

Copyright (C) 2025-2026 VITURE Inc. All rights reserved.

The compiled libraries libglasses.so, libcloud_protocol.so and libcarina_vio.so are proprietary to VITURE Inc. and are redistributed in object code form as part of this app under VITURE's SDK terms. They are not covered by the MIT Licence and you may not use them separately from the app. VITURE's own licence and its open-source notice for the components inside those libraries (Flatbuffers, OpenCV, HIDAPI, yaml-cpp, Eigen, libusb, tiny-AES-c, libuvc, cpp-httplib, Mbed TLS, raylib) are included in the app as "VITURE licence" and "VITURE open-source notice" under Settings, About and legal.

VITURE is a trademark of VITURE Inc. This app is not made, endorsed or supported by VITURE.

## FFmpeg (LGPL 2.1 or later)

The app decodes Dolby TrueHD / MLP and DTS audio with libavcodec, libavutil and libswresample from FFmpeg 6.0 (https://ffmpeg.org), built from the unmodified source for this app with only those decoders enabled. FFmpeg is licensed under the GNU Lesser General Public License, version 2.1 or later (full text in the app: licenses/LGPL-2.1.txt). It is built without any GPL or non-free parts, and is statically linked into libffmpegJNI.so.

In keeping with the LGPL you may replace it with a modified version: the exact FFmpeg release (tag n6.0, https://github.com/FFmpeg/FFmpeg), the build recipe (tools/build_ffmpeg.sh) and the source of the JNI layer that links it are all in this app's source repository, and the app can be rebuilt with your own FFmpeg. The JNI layer (ffmpeg_jni.cc) and its Java classes come from AndroidX Media3 and are under the Apache License 2.0.

## Apache License 2.0

The full licence text is included in the app (licenses/Apache-2.0.txt) and at https://www.apache.org/licenses/LICENSE-2.0

- AndroidX Media3 (ExoPlayer) 1.4.1: media3-common, container, database, datasource, decoder, exoplayer, exoplayer-dash, exoplayer-hls, extractor, and the FFmpeg decoder extension's Java and JNI sources (decoder_ffmpeg, vendored with small marked changes). Copyright The Android Open Source Project. https://github.com/androidx/media
- AndroidX annotation, collection, core, exifinterface. Copyright The Android Open Source Project.
- Android desugar_jdk_libs (core library desugaring) is used at build time and is licensed under GPL-2.0 with the Classpath Exception, which permits this use.

YouTube edition only:

- OkHttp 4.12.0 and Okio. Copyright Square, Inc. https://square.github.io/okhttp/
- Kotlin standard library. Copyright JetBrains s.r.o. and Kotlin contributors.
- jsr305 (findbugs annotations). https://code.google.com/archive/p/jsr-305/

## Other open-source components (YouTube edition only)

- NewPipe Extractor v0.26.5, GNU General Public License v3.0 or later. Copyright the NewPipe contributors. https://github.com/TeamNewPipe/NewPipeExtractor. This is why the YouTube edition is built from source and is not distributed as a binary together with VITURE's libraries (see the README, "Which version do you want?"). Source for the exact version used is available at that address.
- nanojson, Copyright TeamNewPipe and contributors, Apache License 2.0.
- protobuf-javalite, BSD 3-Clause Licence. Copyright Google LLC. https://github.com/protocolbuffers/protobuf
- jsoup 1.22.2, MIT Licence. Copyright Jonathan Hedley and contributors. https://jsoup.org
- Mozilla Rhino 1.8.1, Mozilla Public License 2.0. https://github.com/mozilla/rhino. Source is available at that address.

## Not distributed with the app

The names YouTube, Google, Stremio, VITURE, RayNeo and Pocket TV belong to their owners and are used only to describe what the app works with.
