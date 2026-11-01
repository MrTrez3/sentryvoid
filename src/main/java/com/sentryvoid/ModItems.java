package com.sentryvoid;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, SentryVoidMod.MODID);

    public static final RegistryObject<Item> VOID_CORE = ITEMS.register("void_core",
            () -> new VoidCoreItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC).fireResistant()));
}
