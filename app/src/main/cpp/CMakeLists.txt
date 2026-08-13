cmake_minimum_required(VERSION 3.22.1)

add_subdirectory(winlator)
add_subdirectory(vortekrenderer)
add_subdirectory(virglrenderer)
add_subdirectory(midihandler)
add_subdirectory(libadrenotools)
add_subdirectory(gladiorenderer)

add_executable(steamdroid_supervisor steamdroid_supervisor.c)
target_compile_options(steamdroid_supervisor PRIVATE -fPIE -Wall -Wextra -Werror)
target_link_options(steamdroid_supervisor PRIVATE -pie -Wl,--build-id=sha1)
set_target_properties(steamdroid_supervisor PROPERTIES
    OUTPUT_NAME "steamdroid_supervisor"
    SUFFIX ".bin")

# Put the generated executable in the normal jniLibs input directory so AGP
# tracks its timestamp as a packaging input on incremental builds. The file is
# ignored by Git and is still rebuilt from this source target on every native
# build; the installed copy remains under ApplicationInfo.nativeLibraryDir.
set(STEAMDROID_JNI_OUTPUT "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
add_custom_command(TARGET steamdroid_supervisor POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${STEAMDROID_JNI_OUTPUT}"
    COMMAND ${CMAKE_COMMAND} -E copy "$<TARGET_FILE:steamdroid_supervisor>" "${STEAMDROID_JNI_OUTPUT}/libsteamdroid_supervisor.so")

# AGP's native build task selects the existing shared-library targets. Make
# the immutable executable a dependency of the core JNI library so the
# extracted helper is generated on every normal APK build.
add_dependencies(winlator steamdroid_supervisor)
