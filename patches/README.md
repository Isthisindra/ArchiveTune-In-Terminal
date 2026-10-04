# Patches for the `core` submodule

`core` points at [rukamori/core](https://github.com/rukamori/core), so changes to
it cannot be pushed from this repository. The CLI needs a few edits that live
in that module, so they are kept here as a patch instead of being lost.

## `core-requires-apikey.patch`

Three changes, all required for CLI playback:

1. **`YouTubeClient.requiresApiKey`** - the android/iOS/visionOS innertube
   clients reject player requests that do not carry the public InnerTube key.
2. **`InnerTube.player()`** - sends that key for the clients that require it.
   Without it the CLI's playback client (VISIONOS, guest, no login) answers
   400/404 and no track resolves.
3. **`YouTube`** - reads the account `dataSyncId` under both spellings
   (`dataSyncId` and `datasyncId`) and prefers the delegated value, so a login
   session survives today's account responses.

## Applying

On a fresh checkout:

```sh
git submodule update --init
git -C core apply ../../patches/core-requires-apikey.patch
```

## Publishing instead of patching

Fork `rukamori/core` into your own account, push the same change there, then
point the submodule at your fork:

```sh
git -C core remote add mine <your-fork-url>
git -C core push mine <branch>
# then edit .gitmodules: url = <your-fork-url>
```