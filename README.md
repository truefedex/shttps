# SHTTPS
Simple HTTP Server - Open sourced modules

## Description
This is a Simple HTTP server - a utility originally developed for Android OS for convenient sharing of files and folders between devices, but over time it has also turned out to be useful as a convenient tool for prototyping and creating small single-purpose embedded sites on various Android/Linux-IoT-type home-made devices.

Initially, SHTTPS was closed source software and even had (and still has) a paid version. But now I decided that some people might find it useful to have more options for customizing the behavior of this software, so I opened the source code of the main parts.

## Code structure
This is a multi-module cross-platform Gradle project with sources mostly in Java (and some HTML/JS/CSS for a simple web frontend). I usually edit it in Android Studio. It has the following modules:

- **server-lib**: module library containing a fairly low-level implementation of the HTTP server. Why did I write my own implementation of the HTTP server, and not take an existing one? Well, it just happened historically - I wrote it a long time ago for fun and it was quite small, and I thought it would be cool to embed it somewhere so that the application size would remain small. That's how the first version of SHTTPS came about :)
- **shttps-common**: basic cross-platform classes and interfaces on which SHTTPS is built. They are also used in all application variants. The source code of the static web frontend is also located here in the subfolder "shttps-static-public".
- **shttps-android-common**: here are android implementations of interfaces from the shttps-common module. This also needs to be connected if you are going to create a custom android version of the application.
- **shttps-android-exemplum**: an example of the implementation of a server application for Android similar to a stripped-down version of the SHTTPS from Google Play.
- **shttps-commandline**: command line server version for J2SE platform

All sources are almost not documented yet due to my lack of time for this. I hope with time I will be able to improve the quality of documentation, code and maybe even add some tests 🤔 ...
