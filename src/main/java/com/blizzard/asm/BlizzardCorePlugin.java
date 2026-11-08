package com.blizzard.asm;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import java.util.Map;

@IFMLLoadingPlugin.Name("BlizzardCore")
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.TransformerExclusions("com.blizzard.asm")
public class BlizzardCorePlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[] { "com.blizzard.asm.BlizzardClassTransformer" };
    }

    @Override
    public String getModContainerClass() { return null; }
    @Override
    public String getSetupClass() { return null; }
    @Override
    public void injectData(Map<String, Object> data) {}
    @Override
    public String getAccessTransformerClass() { return null; }
}