package io.github.mjtb49.strongholdtrainer.mixin;

import net.minecraft.block.*;
import net.minecraft.util.registry.Registry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(Blocks.class)
public class MixinBlocks {
    /**
     * @author
     */
    @Overwrite
    private static Block register(String id, Block block) {
        if(id == "end_portal"){
            return (Block) Registry.register(Registry.BLOCK, id, new EndPortalBlock(AbstractBlock.Settings.of(Material.PORTAL, MaterialColor.BLACK).noCollision().lightLevel((state) -> 0).strength(-1.0F, 3600000.0F).dropsNothing()));
        }
        return (Block)Registry.register(Registry.BLOCK, id, block);
    }
}
