package com.blizzard.damage;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.DamageSource;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;

/**
 * Источник урона холодом.
 *
 * Сообщение о смерти: «Игрок X стал дедом морозом».
 * Переопределяем getDeathMessage
 */
public class BlizzardDamageSource extends DamageSource {

    /** Единственный экземпляр (как ванильные статические поля). */
    public static final BlizzardDamageSource COLD = new BlizzardDamageSource();

    private BlizzardDamageSource() {
        super("blizzard_cold");
        this.setDamageBypassesArmor(); //игнор брони
        this.setDamageIsAbsolute(); //игнор всего остального
    }

    @Override
    public ITextComponent getDeathMessage(EntityLivingBase entity) {
        // «Игрок X стал дедом морозом»
        return new TextComponentString(
                entity.getName() + " стал дедом морозом"
        );
    }
}