# Third-party software

Mental is MIT-licensed (see [`LICENSE`](LICENSE)). The distributed
`Mental-<version>.jar` bundles the following third-party components,
shaded and relocated under `me.vexmc.mental.lib.*`. Each remains under
its own license; this file records the attribution and license terms.

| Component | Version | License | Bundled as |
| --- | --- | --- | --- |
| [PacketEvents](https://github.com/retrooper/packetevents) | 2.12.1 | GPL-3.0 | `me.vexmc.mental.lib.packetevents.*` |
| [bStats](https://github.com/Bastian/bStats-Metrics) | 3.2.1 | MIT | `me.vexmc.mental.lib.bstats.*` |
| [Adventure](https://github.com/KyoriPowered/adventure) | 4.9.3 | MIT | `me.vexmc.mental.lib.adventure.*` |
| [JVMDowngrader](https://github.com/unimined/JVMDowngrader) | 1.3.6 | LGPL-2.1 (dual-licensed) | `me.vexmc.mental.lib.jvmdg.*` |

## JVMDowngrader (LGPL-2.1)

The distributed jar is a **Multi-Release mega-jar**: its Java-8 (class v52)
base tree is produced by [JVMDowngrader](https://github.com/unimined/JVMDowngrader),
which also shades a small runtime helper library into the jar (relocated
under `me/vexmc/mental/lib/jvmdg/`). That runtime is:

- **JVMDowngrader**, © William Gray (wagyourtail), dual-licensed under the
  **GNU Lesser General Public License version 2.1 (LGPL-2.1)** and a separate
  commercial license. Source: <https://github.com/unimined/JVMDowngrader>.

The bundled jar is a "Combined Work" in LGPL terms. The **un-downgraded**
artifact (the original Java-17 / class v61 jar, before JVMDowngrader runs) is
buildable from this repository's public source — `./gradlew build` produces it
as the intermediate shadowJar under `core/build/jvmdg-stage/` on the way to the
final mega-jar. Providing that buildable public source satisfies the LGPL §6(a)
relink provision, per the JVMDowngrader author's own
[`LICENSE.md`](https://github.com/unimined/JVMDowngrader/blob/main/LICENSE.md)
guidance ("section 6.a should be satisfied as long as your project provides the
unshaded/undowngraded jar as well, or alternatively provides source code to
build said jar").
