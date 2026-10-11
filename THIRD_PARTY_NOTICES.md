# Third-Party Notices

This file lists third-party source components that are vendored in this repository or built into the app outside the ordinary Gradle dependency metadata.

Gradle dependencies are listed in the app's own open-source licenses screen. That list is
**maintained by hand** in `lyrico-app/src/main/kotlin/com/lonx/lyrico/data/model/AppLicence.kt` (the
Android build generated it with the AboutLibraries plugin; the desktop build has no such plugin), so a
dependency added to `gradle/libs.versions.toml` also has to be added there.

| Component | Usage | License | Notice |
| --- | --- | --- | --- |
| [quickjs-ng](https://github.com/quickjs-ng/quickjs) | Embedded JavaScript runtime for source plugins | MIT | See [LICENSE](./lyrico-app/src/main/cpp/quickjs-ng/LICENSE). |
| [TagLib](https://github.com/taglib/taglib) | Native audio metadata reader/writer used by `lyrico-audiotag` | LGPL-2.1 or MPL-1.1 | See [COPYING.LGPL](./lyrico-audiotag/src/main/cpp/taglib/COPYING.LGPL) and [COPYING.MPL](./lyrico-audiotag/src/main/cpp/taglib/COPYING.MPL). |
| [libebur128](https://github.com/jiixyj/libebur128) | EBU R128 loudness measurement | MIT | See [COPYING](./lyrico-app/src/main/cpp/ebur128/COPYING). |
