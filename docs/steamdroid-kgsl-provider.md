# SteamDroid KGSL Turnip provider

Thor exposes the Android KGSL device (`/dev/kgsl-3d0`) while the pinned Holo
Mesa package is built for the `msm` DRM backend. SteamDroid therefore ships a
separate glibc Vulkan provider and selects it with the session-local
`/opt/steamdroid-kgsl-driver/freedreno-kgsl.icd.json` descriptor. The provider
is not the Android/bionic driver from shared storage.

The checked-in provider was built from Mesa `mesa-25.2.7` with the sibling
`steam-android-runtime-research/android/nova-lab/build-kgsl-turnip.sh` build
configuration:

```text
-Dplatforms=x11
-Dvulkan-drivers=freedreno
-Dfreedreno-kmds=kgsl
-Dglx=disabled -Degl=disabled -Dgbm=disabled
```

Asset provenance:

```text
asset: app/src/main/assets/steamdroid/kgsl/libvulkan_freedreno.so
size: 12373576 bytes
sha256: 2e5eb2f991b4991236ff67cd69745a921ec114654c635656f9d246038ed30bf5
architecture: ELF64 AArch64 ET_DYN
```

`SteamKgslProviderProvisioner` verifies the size, ELF identity, SHA-256 and
ICD path before atomically installing the provider under the Holo root. A
future Mesa refresh must update the channel descriptor, asset, and this
provenance record together, then repeat the Thor GPU-topology acceptance.
