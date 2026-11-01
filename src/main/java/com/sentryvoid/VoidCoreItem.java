package com.sentryvoid;

import java.util.List;
import java.util.function.Predicate;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Nucleo do Void.
 * Botao direito: usa a habilidade selecionada.
 * Shift + botao direito: troca de habilidade.
 */
public class VoidCoreItem extends Item {

    private static final String KEY_ABILITY = "VoidAbility";
    private static final String KEY_FORM = "VoidForm";

    public enum Ability {
        FORM("form", 20, 0.0f),
        BLAST("blast", 40, 1.5f),
        GRASP("grasp", 60, 1.0f),
        DASH("dash", 50, 1.0f),
        SINGULARITY("singularity", 200, 4.0f),
        ENGULF("engulf", 600, 8.0f),
        ERASE("erase", 400, 6.0f);

        public final String id;
        public final int cooldown;
        public final float exhaustion;

        Ability(String id, int cooldown, float exhaustion) {
            this.id = id;
            this.cooldown = cooldown;
            this.exhaustion = exhaustion;
        }

        public Component displayName() {
            return Component.translatable("ability.sentryvoid." + id);
        }

        public static Ability byIndex(int i) {
            Ability[] v = values();
            return v[Math.floorMod(i, v.length)];
        }
    }

    public VoidCoreItem(Properties properties) {
        super(properties);
    }

    // ---------- estado salvo no item ----------

    private static int getIndex(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getInt(KEY_ABILITY);
    }

    private static boolean isForm(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getBoolean(KEY_FORM);
    }

    private static void setForm(ItemStack stack, boolean on) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean(KEY_FORM, on));
    }

    // ---------- uso ----------

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide() && level instanceof ServerLevel server) {
            if (player.isShiftKeyDown()) {
                int next = (getIndex(stack) + 1) % Ability.values().length;
                CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putInt(KEY_ABILITY, next));
                player.displayClientMessage(
                        Component.translatable("message.sentryvoid.selected", Ability.byIndex(next).displayName())
                                .withStyle(ChatFormatting.DARK_PURPLE), true);
                server.playSound(null, player.blockPosition(), SoundEvents.UI_BUTTON_CLICK.value(),
                        SoundSource.PLAYERS, 0.6f, 0.5f);
            } else {
                activate(server, player, stack);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    private void activate(ServerLevel level, Player player, ItemStack stack) {
        Ability ability = Ability.byIndex(getIndex(stack));
        boolean creative = player.getAbilities().instabuild;

        if (!creative && ability != Ability.FORM && player.getFoodData().getFoodLevel() <= 6) {
            player.displayClientMessage(Component.translatable("message.sentryvoid.too_weak")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }

        boolean ok = switch (ability) {
            case FORM -> toggleForm(level, player, stack);
            case BLAST -> blast(level, player);
            case GRASP -> grasp(level, player);
            case DASH -> dash(level, player);
            case SINGULARITY -> singularity(level, player);
            case ENGULF -> engulf(level, player);
            case ERASE -> erase(level, player);
        };

        if (ok) {
            if (!creative) {
                player.causeFoodExhaustion(ability.exhaustion);
            }
            player.getCooldowns().addCooldown(this, ability.cooldown);
        }
    }

    // ---------- habilidades ----------

    private static boolean toggleForm(ServerLevel level, Player player, ItemStack stack) {
        boolean on = !isForm(stack);
        setForm(stack, on);
        player.displayClientMessage(Component.translatable(
                on ? "message.sentryvoid.form_on" : "message.sentryvoid.form_off")
                .withStyle(ChatFormatting.DARK_PURPLE), true);
        level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.0f, on ? 0.4f : 0.8f);
        level.sendParticles(ParticleTypes.SQUID_INK, player.getX(), player.getY() + 1.0, player.getZ(),
                60, 0.5, 0.9, 0.5, 0.08);
        return true;
    }

    /** Raio de escuridao: dano magico + Darkness + Wither. */
    private static boolean blast(ServerLevel level, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double dist = rayDistance(level, player, 40.0);

        for (double d = 1.5; d < dist; d += 0.6) {
            Vec3 p = eye.add(look.scale(d)).add(0, -0.2, 0);
            level.sendParticles(ParticleTypes.SQUID_INK, p.x, p.y, p.z, 2, 0.08, 0.08, 0.08, 0.0);
            level.sendParticles(ParticleTypes.SMOKE, p.x, p.y, p.z, 1, 0.1, 0.1, 0.1, 0.0);
        }

        LivingEntity target = findTarget(player, dist);
        if (target != null) {
            target.hurt(voidDamage(level, player), 10.0f);
            target.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100));
            target.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 1));
            target.push(look.x * 0.8, 0.3, look.z * 0.8);
            target.hurtMarked = true;
        }
        level.playSound(null, player.blockPosition(), SoundEvents.WARDEN_SONIC_BOOM,
                SoundSource.PLAYERS, 1.0f, 0.6f);
        return true;
    }

    /** Tentaculo de sombra: puxa o alvo ate voce. */
    private static boolean grasp(ServerLevel level, Player player) {
        LivingEntity target = findTarget(player, rayDistance(level, player, 24.0));
        if (target == null) {
            player.displayClientMessage(Component.translatable("message.sentryvoid.no_target")
                    .withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        Vec3 from = target.position().add(0, target.getBbHeight() / 2, 0);
        Vec3 to = player.position().add(0, player.getBbHeight() / 2, 0);
        Vec3 dir = to.subtract(from);
        double len = dir.length();
        for (double d = 0; d < len; d += 0.5) {
            Vec3 p = from.add(dir.scale(d / len));
            level.sendParticles(ParticleTypes.SQUID_INK, p.x, p.y, p.z, 2, 0.05, 0.05, 0.05, 0.0);
        }
        Vec3 pull = dir.normalize().scale(1.6).add(0, 0.3, 0);
        target.setDeltaMovement(pull);
        target.hurtMarked = true;
        target.hurt(voidDamage(level, player), 2.0f);
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2));
        level.playSound(null, player.blockPosition(), SoundEvents.SCULK_SHRIEKER_SHRIEK,
                SoundSource.PLAYERS, 0.6f, 1.4f);
        return true;
    }

    /** Impulso do Void: arranca na direcao do olhar. */
    private static boolean dash(ServerLevel level, Player player) {
        Vec3 look = player.getLookAngle();
        player.setDeltaMovement(look.scale(2.4));
        player.hurtMarked = true;
        player.fallDistance = 0.0F;
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 80, 0, true, false));
        level.sendParticles(ParticleTypes.SQUID_INK, player.getX(), player.getY() + 1.0, player.getZ(),
                50, 0.4, 0.6, 0.4, 0.15);
        level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.0f, 0.3f);
        return true;
    }

    /** Singularidade: implode tudo ao redor do ponto mirado. */
    private static boolean singularity(ServerLevel level, Player player) {
        Vec3 eye = player.getEyePosition();
        double dist = Math.min(rayDistance(level, player, 30.0), 30.0);
        Vec3 center = eye.add(player.getLookAngle().scale(dist));

        List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class,
                new AABB(center, center).inflate(12.0), e -> e != player && e.isAlive());
        for (LivingEntity e : victims) {
            Vec3 to = center.subtract(e.position().add(0, e.getBbHeight() / 2, 0));
            double len = to.length();
            Vec3 v = len > 0.5 ? to.normalize().scale(Math.min(2.0, 0.4 + len * 0.15)) : Vec3.ZERO;
            e.setDeltaMovement(v);
            e.hurtMarked = true;
            e.hurt(voidDamage(level, player), 6.0f);
            e.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 100));
        }

        level.sendParticles(ParticleTypes.REVERSE_PORTAL, center.x, center.y, center.z, 120, 1.5, 1.5, 1.5, 0.15);
        for (int i = 0; i < 48; i++) {
            double a = Math.PI * 2 * i / 48.0;
            level.sendParticles(ParticleTypes.SQUID_INK, center.x + Math.cos(a) * 4, center.y,
                    center.z + Math.sin(a) * 4, 1, 0, 0, 0, 0.0);
        }
        level.playSound(null, center.x, center.y, center.z, SoundEvents.ENDERMAN_TELEPORT,
                SoundSource.PLAYERS, 1.5f, 0.3f);
        return true;
    }

    /** O Void engole a area: escuridao, lentidao e fraqueza em todos ao redor. */
    private static boolean engulf(ServerLevel level, Player player) {
        List<LivingEntity> victims = level.getEntitiesOfClass(LivingEntity.class,
                player.getBoundingBox().inflate(20.0), e -> e != player && e.isAlive());
        for (LivingEntity e : victims) {
            e.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 300));
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 1));
            e.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200));
            e.hurt(voidDamage(level, player), 4.0f);
        }
        player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 100, 1));
        level.sendParticles(ParticleTypes.SQUID_INK, player.getX(), player.getY() + 1.0, player.getZ(),
                400, 8.0, 3.0, 8.0, 0.05);
        level.sendParticles(ParticleTypes.SCULK_SOUL, player.getX(), player.getY() + 1.0, player.getZ(),
                80, 6.0, 2.0, 6.0, 0.03);
        level.playSound(null, player.blockPosition(), SoundEvents.WITHER_SPAWN,
                SoundSource.PLAYERS, 0.7f, 0.5f);
        return true;
    }


    /** Apagar: transforma o alvo em sombra e o remove do mundo. */
    private static boolean erase(ServerLevel level, Player player) {
        LivingEntity target = findTarget(player, rayDistance(level, player, 30.0));
        if (target == null) {
            player.displayClientMessage(Component.translatable("message.sentryvoid.no_target")
                    .withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        if (target instanceof Player other && (other.isCreative() || other.isSpectator())) {
            player.displayClientMessage(Component.translatable("message.sentryvoid.immune")
                    .withStyle(ChatFormatting.GRAY), true);
            return false;
        }

        double x = target.getX();
        double y = target.getY();
        double z = target.getZ();
        double h = target.getBbHeight();
        double w = Math.max(0.3, target.getBbWidth() / 2.0);

        // silhueta de sombra que se desfaz subindo
        level.sendParticles(ParticleTypes.SQUID_INK, x, y + h / 2, z, 120, w, h / 2, w, 0.04);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + h / 2, z, 40, w, h / 2, w, 0.02);
        level.sendParticles(ParticleTypes.SCULK_SOUL, x, y + h / 2, z, 30, w, h / 2, w, 0.05);
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y + h / 2, z, 60, w, h / 2, w, 0.1);
        level.playSound(null, x, y, z, SoundEvents.WITHER_DEATH, SoundSource.PLAYERS, 0.5f, 0.4f);

        if (target instanceof EnderDragon || target instanceof WitherBoss) {
            // chefes nao sao apagados, mas sofrem muito
            target.hurt(voidDamage(level, player), 100.0f);
        } else if (target instanceof Player) {
            target.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
        } else {
            target.discard(); // some sem drops
        }
        return true;
    }

    // ---------- forma do Void (passiva enquanto ativa) ----------

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (level.isClientSide() || !(level instanceof ServerLevel server)
                || !(entity instanceof ServerPlayer player) || !isForm(stack)) {
            return;
        }

        if (!player.getAbilities().instabuild && player.getFoodData().getFoodLevel() <= 6) {
            setForm(stack, false);
            player.displayClientMessage(Component.translatable("message.sentryvoid.form_off")
                    .withStyle(ChatFormatting.DARK_PURPLE), true);
            return;
        }

        if (player.tickCount % 20 == 0) {
            player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 1, true, false));
            player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 60, 1, true, false));
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 60, 1, true, false));
            player.addEffect(new MobEffectInstance(MobEffects.JUMP, 60, 2, true, false));
            player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 60, 0, true, false));
            player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 260, 0, true, false));
            if (!player.getAbilities().instabuild) {
                player.causeFoodExhaustion(0.4f);
            }
        }
        if (player.tickCount % 4 == 0) {
            server.sendParticles(ParticleTypes.SQUID_INK, player.getX(), player.getY() + 1.0, player.getZ(),
                    5, 0.35, 0.7, 0.35, 0.02);
            server.sendParticles(ParticleTypes.SMOKE, player.getX(), player.getY() + 0.3, player.getZ(),
                    2, 0.3, 0.1, 0.3, 0.01);
        }
    }

    // ---------- utilidades ----------

    private static DamageSource voidDamage(ServerLevel level, Player player) {
        return level.damageSources().indirectMagic(player, player);
    }

    /** Distancia ate o primeiro bloco na linha de visao (ou max). */
    private static double rayDistance(ServerLevel level, Player player, double max) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(max));
        BlockHitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS ? max : eye.distanceTo(hit.getLocation());
    }

    private static LivingEntity findTarget(Player player, double range) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.scale(range));
        AABB box = player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        Predicate<Entity> filter = e -> e instanceof LivingEntity && e != player && !e.isSpectator() && e.isPickable();
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box, filter, range * range);
        return hit != null && hit.getEntity() instanceof LivingEntity le ? le : null;
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.sentryvoid.current",
                Ability.byIndex(getIndex(stack)).displayName()).withStyle(ChatFormatting.DARK_PURPLE));
        tooltip.add(Component.translatable("tooltip.sentryvoid.hint").withStyle(ChatFormatting.GRAY));
    }
}
