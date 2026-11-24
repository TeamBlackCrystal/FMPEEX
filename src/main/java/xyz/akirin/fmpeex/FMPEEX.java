package xyz.akirin.fmpeex;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLInterModComms;
import cpw.mods.fml.common.registry.GameData;

/**
 * Mod list entry. Nearly all of the work happens in {@link xyz.akirin.fmpeex.core.FMPEEXTransformer}; the rest is
 * telling other mods about PEEX where PEEX itself predates them.
 */
@Mod(
    modid = FMPEEX.MODID,
    name = "FMPEEX",
    version = Tags.VERSION,
    dependencies = "required-after:ProjectE;after:PEEX",
    acceptableRemoteVersions = "*")
public class FMPEEX {

    public static final String MODID = "fmpeex";

    private static final Logger LOG = LogManager.getLogger(MODID);

    /** The page id of FMProjectE's NEI page for collector fuel upgrades. */
    private static final String COLLECTOR_FUEL_PAGE = "collectorFuelUpgrade";

    /** PEEX's collectors, weakest first. They are FMProjectE collectors underneath and upgrade the same fuels. */
    private static final String[] PEEX_COLLECTORS = { "collectorMk6", "collectorMk7", "collectorMk8", "collectorMk9",
        "collectorMk10", "collectorFinal" };

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        if (Loader.isModLoaded("PEEX") && Loader.isModLoaded("NotEnoughItems")) {
            registerCollectorsWithNEI();
        }
    }

    /**
     * Lists PEEX's collectors beside FMProjectE's "Collector Fuel Upgrade" page in NEI. Sent as IMC, so no NEI class is
     * touched, and NEI ignores it when FMProjectE is too old to have that page.
     */
    private static void registerCollectorsWithNEI() {
        try {
            Class<?> blocks = Class.forName("akuto2.peex.ObjHandlerPEEX");
            for (int i = 0; i < PEEX_COLLECTORS.length; i++) {
                Object block = blocks.getField(PEEX_COLLECTORS[i])
                    .get(null);
                String name = block instanceof Block ? GameData.getBlockRegistry()
                    .getNameForObject(block) : null;
                if (name == null) {
                    LOG.warn("PEEX collector {} is not registered, leaving it out of NEI", PEEX_COLLECTORS[i]);
                    continue;
                }
                NBTTagCompound catalyst = new NBTTagCompound();
                catalyst.setString("handlerID", COLLECTOR_FUEL_PAGE);
                catalyst.setString("itemName", name);
                // After FMProjectE's own three collectors, in tier order
                catalyst.setInteger("priority", -10 - i);
                FMLInterModComms.sendMessage("NotEnoughItems", "registerCatalystInfo", catalyst);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.warn("Could not list PEEX's collectors in NEI", e);
        }
    }
}
