# Dynmap® - dynamic web maps for Minecraft servers

Dynmap has always been my favorite minecraft mod. I've been using it for almost as long as I've been playing minecraft.
Unfortunately I've ran into a handful of critical bugs using upstream Dynmap, rendering it unusable for me.

It seems like Dynmap development has stalled, as a number of critical bugs do have open issues for them, and some have open PRs written by the community.

So I'm forking Dynmap and fixing some of them. I love the idea of upstreaming them if the maintainers come back, but 
a fundamental issue still remains which is that I'm not able to test on old java versions, on bukkit/spigot/paper, on old versions of fabric or forge, or on Windows.

All of those are target platforms for upstream Dynmap. In addition I'm also making a lot of noise in this repo that should not be upstreamed.

# Where do I get this version

Build it yourself. Releasing pure bugfixes of Dynmap is not allowed as per Dynmap's licence (read the original licence stuff from the README below).

# Porting, Supporting Other Platforms, Customized Dynmap Builds

While Dynmap is open source, subject to the Apache Public License, v2, the Dynmap team does have specific policies and
requirements for anyone that would
use the code here for anything except building contributions submitted back to this code base as Pull Requests (which is
the only process by which code is accepted and can become part of a release supported by the Dynmap team). Other
authorized uses include:

- Building custom version of Dynmap for use on a personal or on a specific server, so long as this version is NOT
  distributed to other parties.
  The modifying team agrees to not pursue support from the Dynmap team for this modified private version, but is
  otherwise not required to share the
  modified source code - though doing so is encouraged.
- Building a modified version of Dynmap for otherwise unsupported platforms: in this event, the modified version MUST be
  for a platform or version
  not yet (or no longer) supported by the Dynmap team. If the Dynmap team comes to support this platform or version, the
  modifying team must agree to
  cease distribution of the unofficial version, unless otherwise authorized to continue doing so. Further:
    - The team distributing the modified version must cite the origin of the Dynmap code, but must also clearly indicate
      that the version is NOT supported by
      nor endorsed by the Dynmap team, and that ALL support should be directed through the team providing the modified
      version.
    - Any modified version CANNOT be monetized or otherwise charged for, under any circumstances, nor can redistribution
      of it be limited or restricted.
    - The modified code must continue to be Apache Public License v2, with no additional conditions or restrictions,
      including full public availability of the
      modified source code.
    - Any code from Dynmap used in such versions should be built from an appropriate fork, as DynmapCore and other
      components (other than DynmapCoreAPI and
      dynmap-api) are subject to breaking changes at any time, and the support messages in DynmapCore MUST be modified
      to refer to the supporting team (or, at
      least, removed). The modified version should NOT refer to the Dynmap Discord nor to /r/Dynmap on Reddit for
      support. in any case.
    - Any bugs or issues opened in conjunction with the use of the modified version on this repository will be closed
      without comment.

Additions of new functions, including new platform support, in this official Dynmap code base MUST be fully contained
within the PRs submitted to this
repository. Further, it is always expected than any updates will be built and tested across all relevant platforms -
meaning any chances to shared code
components (DynmapCore, DynmapCoreAPI) MUST be successfully built and tested on ALL supported platforms (Forge, Spigot,
etc). Changes which break
supported platforms will be rejected.

The only interfaces published and maintained as 'stable' are the interfaces of the DynmapCoreAPI (cross platform) and
dynmap-api (Bukkit/spigot specific)
libraries. All other components are NOT libraries - DynmapCore, in particular, is a shared code component across the
various platforms, but is subject to
breaking changes without warning or consideration - any use of DynmapCore interfaces by code outside this repository is
NOT supported, and will likely
result in breaking of such consuming code without warning and without apology. DynmapCore is an internal shared code
component, not a library - please
treat it accordingly.

Plugins or mods using the published APIs - DynmapCoreAPI (for all platforms) or dynmap-api (only for Spigot/Bukkit) -
may access these components as
'compile' dependencies: DO NOT INTEGRATE THEM INTO YOUR PLUGIN - this will break Dynmap and/or other plugins when these
interfaces are updated or
expanded. These libraries are published at https://repo.mikeprimm.com and will be updated each official release.
