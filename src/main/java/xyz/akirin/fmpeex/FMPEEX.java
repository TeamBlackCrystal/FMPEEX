package xyz.akirin.fmpeex;

import cpw.mods.fml.common.Mod;

/**
 * Mod list entry. All of the work happens in {@link xyz.akirin.fmpeex.core.FMPEEXTransformer}.
 */
@Mod(
    modid = FMPEEX.MODID,
    name = "FMPEEX",
    version = Tags.VERSION,
    dependencies = "required-after:ProjectE;after:PEEX",
    acceptableRemoteVersions = "*")
public class FMPEEX {

    public static final String MODID = "fmpeex";
}
