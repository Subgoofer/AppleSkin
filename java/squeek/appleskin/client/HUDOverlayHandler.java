package squeek.appleskin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TickEvent;
import org.lwjgl.opengl.GL11;
import squeek.appleskin.ModConfig;
import squeek.appleskin.ModInfo;
import squeek.appleskin.api.event.HUDOverlayEvent;
import squeek.appleskin.helpers.FoodHelper;
import squeek.appleskin.helpers.HungerHelper;
import squeek.appleskin.helpers.TextureHelper;
import squeek.appleskin.util.IntPoint;

import java.util.Vector;

@OnlyIn(Dist.CLIENT)
public class HUDOverlayHandler
{
	private static float unclampedFlashAlpha = 0f;
	private static float flashAlpha = 0f;
	private static byte alphaDir = 1;
	protected static int foodIconsOffset;
	protected static int healthIconsOffset;

	public static final Vector<IntPoint> healthBarOffsets = new Vector<>();
	public static final Vector<IntPoint> foodBarOffsets = new Vector<>();

	private static final RandomSource random = RandomSource.create();

	public static void register(RegisterGuiLayersEvent event)
	{
		// register setup overlays.
		event.registerBelow(VanillaGuiLayers.PLAYER_HEALTH, HealthOverlayPre.ID, new HealthOverlayPre());
		event.registerBelow(VanillaGuiLayers.FOOD_LEVEL, HungerOverlayPre.ID,new HungerOverlayPre());

		// register render overlays.
		event.registerAbove(VanillaGuiLayers.PLAYER_HEALTH, HealthOverlay.ID, new HealthOverlay());
		event.registerAbove(VanillaGuiLayers.FOOD_LEVEL, HungerOverlay.ID, new HungerOverlay());
		event.registerAbove(VanillaGuiLayers.FOOD_LEVEL, SaturationOverlay.ID, new SaturationOverlay());
		event.registerBelow(VanillaGuiLayers.FOOD_LEVEL, ExhaustionOverlay.ID, new ExhaustionOverlay());

		// register tick callback.
		NeoForge.EVENT_BUS.addListener(HUDOverlayHandler::onClientTick);
	}

	public static abstract class Overlay implements LayeredDraw.Layer
	{
		public abstract void render(Player player, GuiGraphics guiGraphics, int left, int right, int top, int guiTicks);

		@Override
		public final void render(GuiGraphics guiGraphics, float partialTicks)
		{
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || !shouldRenderOverlay(mc.player, mc, guiGraphics))
				return;

			int top = guiGraphics.guiHeight();
			int left = guiGraphics.guiWidth() / 2 - 91; // left of health bar
			int right = guiGraphics.guiWidth() / 2 + 91;// right of food bar

			render(mc.player, guiGraphics, left, right, top, mc.gui.getGuiTicks());
		}

		public boolean shouldRenderOverlay(Player player, Minecraft mc, GuiGraphics guiGraphics)
		{
			return true;
		}
	}

	public static class HealthOverlayPre extends Overlay
	{
		public static final ResourceLocation ID = new ResourceLocation(ModInfo.MODID, "health_setup");

		@Override
		public void render(Player player, GuiGraphics guiGraphics, int left, int right, int top, int guiTicks)
		{
			healthIconsOffset = Minecraft.getInstance().gui.leftHeight;
			// TODO: missing healthBlinkTime, check in net.minecraft.client.gui.Gui#renderHealthLevel
			generateHealthBarOffsets(top - healthIconsOffset, left, right, guiTicks, player);
		}
	}

	public static class HealthOverlay extends Overlay {
		public static final ResourceLocation ID = new ResourceLocation(ModInfo.MODID, "health_restored");

		@Override
		public void render(Player player, GuiGraphics guiGraphics, int left, int right, int top, int guiTicks)
		{
			// draw health overlay if needed
			if (!shouldShowEstimatedHealth(player))
				return;

			FoodHelper.QueriedFoodResult result = queryRenderHeldFood(player);
			if (result == null)
			{
				resetFlash();
				return;
			}

			float foodHealthIncrement = FoodHelper.getEstimatedHealthIncrement(player, result.modifiedFoodProperties);
			float currentHealth = player.getHealth();
			float modifiedHealth = Math.min(currentHealth + foodHealthIncrement, player.getMaxHealth());

			// only create object when the estimated health is successfully
			HUDOverlayEvent.HealthRestored healthRenderEvent = null;
			if (currentHealth < modifiedHealth)
				healthRenderEvent = new HUDOverlayEvent.HealthRestored(modifiedHealth, result.itemStack, result.modifiedFoodProperties, left, top - healthIconsOffset, guiGraphics);

			// notify everyone that we should render estimated health hud
			if (healthRenderEvent != null)
				NeoForge.EVENT_BUS.post(healthRenderEvent);

			if (healthRenderEvent != null && !healthRenderEvent.isCanceled())
				drawHealthOverlay(healthRenderEvent, player, flashAlpha);
		}

		@Override
		public boolean shouldRenderOverlay(Player player, Minecraft mc, GuiGraphics guiGraphics)
		{
			// hide when is mounted.
			if (player.getVehicle() instanceof LivingEntity)
				return false;

			// Offsets size is set to zero intentionally to disable rendering when health is infinite.
			if (healthBarOffsets.isEmpty())
				return false;

			return ModConfig.SHOW_FOOD_HEALTH_HUD_OVERLAY.get();
		}
	}

	public static class HungerOverlayPre extends Overlay
	{
		public static final ResourceLocation ID = new ResourceLocation(ModInfo.MODID, "hunger_setup");

		@Override
		public void render(Player player, GuiGraphics guiGraphics, int left, int right, int top, int guiTicks)
		{
			foodIconsOffset = Minecraft.getInstance().gui.rightHeight;
			generateHungerBarOffsets(top - foodIconsOffset, left, right, guiTicks, player);
		}
	}

	public static class HungerOverlay extends Overlay
	{
		public static final ResourceLocation ID = new ResourceLocation(ModInfo.MODID, "hunger_restored");

		@Override
		public void render(Player player, GuiGraphics guiGraphics, int left, int right, int top, int guiTicks)
		{
			FoodData stats = player.getFoodData();
			FoodHelper.QueriedFoodResult result = queryRenderHeldFood(player);
			if (result == null)
			{
				resetFlash();
				return;
			}

			ItemStack heldItem = result.itemStack;
			FoodProperties modifiedFoodProperties = result.modifiedFoodProperties;

			// notify everyone that we should render hunger hud overlay
			HUDOverlayEvent.HungerRestored renderRenderEvent = new HUDOverlayEvent.HungerRestored(stats.getFoodLevel(), heldItem, modifiedFoodProperties, right, top - foodIconsOffset, guiGraphics);
			NeoForge.EVENT_BUS.post(renderRenderEvent);
			if (renderRenderEvent.isCanceled())
				return;

			// calculate the final hunger and saturation
			int foodHunger = modifiedFoodProperties.nutrition();
			float foodSaturationIncrement = modifiedFoodProperties.saturation();

			// restored hunger/saturation overlay while holding food
			drawHungerOverlay(renderRenderEvent, player, foodHunger, flashAlpha, FoodHelper.isRotten(modifiedFoodProperties));
		}

		@Override
		public boolean shouldRenderOverlay(Player player, Minecraft mc, GuiGraphics guiGraphics)
		{
			return ModConfig.SHOW_FOOD_VALUES_OVERLAY.get();
		}
	}

	public static class SaturationOverlay extends Overlay
	{
		public static final ResourceLocation ID = new ResourceLocation(ModInfo.MODID, "saturation_level");

		@Override
		public void render(Player player, GuiGraphics guiGraphics, int left, int right, int top, int guiTicks)
		{
			FoodData stats = player.getFoodData();
			HUDOverlayEvent.Saturation saturationRenderEvent = new HUDOverlayEvent.Saturation(stats.getSaturationLevel(), right, top - foodIconsOffset, guiGraphics);

			// notify everyone that we should render saturation hud overlay
			if (!saturationRenderEvent.isCanceled())
				NeoForge.EVENT_BUS.post(saturationRenderEvent);

			// the render saturation event maybe cancelled by other mods
			if (saturationRenderEvent.isCanceled())
				return;

			drawSaturationOverlay(saturationRenderEvent, player, 0, 1f);
			FoodHelper.QueriedFoodResult result = queryRenderHeldFood(player);
			if (result == null)
				return;

			// calculate the final hunger and saturation
			FoodProperties modifiedFoodProperties = result.modifiedFoodProperties;
			int foodHunger = modifiedFoodProperties.nutrition();
			float foodSaturationIncrement = modifiedFoodProperties.saturation();

			int newFoodValue = stats.getFoodLevel() + foodHunger;
			float newSaturationValue = stats.getSaturationLevel() + foodSaturationIncrement;
			float saturationGained = newSaturationValue > newFoodValue ? newFoodValue - stats.getSaturationLevel() : foodSaturationIncrement;
			// Redraw saturation overlay for gained
			drawSaturationOverlay(saturationRenderEvent, player, saturationGained, flashAlpha);
		}

		@Override
		public boolean shouldRenderOverlay(Player player, Minecraft mc, GuiGraphics guiGraphics) {
			return ModConfig.SHOW_SATURATION_OVERLAY.get();
		}
	}

	public static class ExhaustionOverlay extends Overlay
	{
		public static final ResourceLocation ID = new ResourceLocation(ModInfo.MODID, "exhaustion_level");

		@Override
		public void render(Player player, GuiGraphics guiGraphics, int left, int right, int top, int guiTicks)
		{
			float exhaustion = player.getFoodData().getExhaustionLevel();

			// Notify everyone that we should render exhaustion hud overlay
			HUDOverlayEvent.Exhaustion renderEvent = new HUDOverlayEvent.Exhaustion(exhaustion, right, top - foodIconsOffset, guiGraphics);
			NeoForge.EVENT_BUS.post(renderEvent);
			if (!renderEvent.isCanceled())
				drawExhaustionOverlay(renderEvent, player, 1f);
		}

		@Override
		public boolean shouldRenderOverlay(Player player, Minecraft mc, GuiGraphics guiGraphics)
		{
			// hide when is mounted.
			if (player.getVehicle() instanceof LivingEntity)
				return false;

			return ModConfig.SHOW_FOOD_EXHAUSTION_UNDERLAY.get();
		}
	}

	public static void drawSaturationOverlay(float saturationGained, float saturationLevel, Player player, GuiGraphics guiGraphics, int right, int top, float alpha)
	{
		if (saturationLevel + saturationGained < 0)
			return;

		enableAlpha(alpha);

		float modifiedSaturation = Math.max(0, Math.min(saturationLevel + saturationGained, 20));

		int startSaturationBar = 0;
		int endSaturationBar = (int) Math.ceil(modifiedSaturation / 2.0F);

		// when require rendering the gained saturation, start should relocation to current saturation tail.
		if (saturationGained != 0)
			startSaturationBar = (int) Math.max(saturationLevel / 2.0F, 0);

		int iconSize = 9;

		for (int i = startSaturationBar; i < endSaturationBar; ++i) {
			// gets the offset that needs to be render of icon
			IntPoint offset = foodBarOffsets.get(i);
			if (offset == null)
				continue;

			int x = right + offset.x;
			int y = top + offset.y;

			int v = 0;
			int u = 0;

			float effectiveSaturationOfBar = (modifiedSaturation / 2.0F) - i;

			if (effectiveSaturationOfBar >= 1)
				u = 3 * iconSize;
			else if (effectiveSaturationOfBar > .5)
				u = 2 * iconSize;
			else if (effectiveSaturationOfBar > .25)
				u = 1 * iconSize;

			guiGraphics.blit(TextureHelper.MOD_ICONS, x, y, u, v, iconSize, iconSize);
		}

		disableAlpha(alpha);
	}

	public static void drawHungerOverlay(int hungerRestored, int foodLevel, Player player, GuiGraphics guiGraphics, int right, int top, float alpha, boolean useRottenTextures)
	{
		if (hungerRestored <= 0)
			return;

		enableAlpha(alpha);

		int modifiedFood = Math.max(0, Math.min(20, foodLevel + hungerRestored));

		int startFoodBars = Math.max(0, foodLevel / 2);
		int endFoodBars = (int) Math.ceil(modifiedFood / 2.0F);

		int iconStartOffset = 16;
		int iconSize = 9;

		for (int i = startFoodBars; i < endFoodBars; ++i)
		{
			// gets the offset that needs to be render of icon
			IntPoint offset = foodBarOffsets.get(i);
			if (offset == null)
				continue;

			int x = right + offset.x;
			int y = top + offset.y;

			ResourceLocation backgroundSprite = TextureHelper.getFoodTexture(useRottenTextures, TextureHelper.FoodType.EMPTY);

			// very faint background
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha * 0.25F);
			guiGraphics.blitSprite(backgroundSprite, x, y, iconSize, iconSize);
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);

			boolean isHalf = i * 2 + 1 == modifiedFood;
			ResourceLocation iconSprite = TextureHelper.getFoodTexture(useRottenTextures, isHalf ? TextureHelper.FoodType.HALF : TextureHelper.FoodType.FULL);

			guiGraphics.blitSprite(iconSprite, x, y, iconSize, iconSize);
		}

		disableAlpha(alpha);
	}

	public static void drawHealthOverlay(float health, float modifiedHealth, Player player, GuiGraphics guiGraphics, int right, int top, float alpha)
	{
		if (modifiedHealth <= health)
			return;

		enableAlpha(alpha);

		int fixedModifiedHealth = (int) Math.ceil(modifiedHealth);
		boolean isHardcore = player.level().getLevelData().isHardcore();

		int startHealthBars = (int) Math.max(0, (Math.ceil(health) / 2.0F));
		int endHealthBars = (int) Math.max(0, Math.ceil(modifiedHealth / 2.0F));

		int iconStartOffset = 16;
		int iconSize = 9;

		for (int i = startHealthBars; i < endHealthBars; ++i) {
			// gets the offset that needs to be render of icon
			IntPoint offset = healthBarOffsets.get(i);
			if (offset == null)
				continue;

			int x = right + offset.x;
			int y = top + offset.y;

			ResourceLocation backgroundSprite = TextureHelper.getHeartTexture(isHardcore, TextureHelper.HeartType.CONTAINER);

			// very faint background
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha * 0.25F);
			guiGraphics.blitSprite(backgroundSprite, x, y, iconSize, iconSize);
			RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);

			boolean isHalf = i * 2 + 1 == fixedModifiedHealth;
			ResourceLocation iconSprite = TextureHelper.getHeartTexture(isHardcore, isHalf ? TextureHelper.HeartType.HALF : TextureHelper.HeartType.FULL);

			guiGraphics.blitSprite(iconSprite, x, y, iconSize, iconSize);
		}

		disableAlpha(alpha);
	}

	public static void drawExhaustionOverlay(float exhaustion, Player player, GuiGraphics guiGraphics, int right, int top, float alpha)
	{
		float maxExhaustion = HungerHelper.getMaxExhaustion(player);
		// clamp between 0 and 1
		float ratio = Math.min(1, Math.max(0, exhaustion / maxExhaustion));
		int width = (int) (ratio * 81);
		int height = 9;

		enableAlpha(.75f);
		guiGraphics.blit(TextureHelper.MOD_ICONS, right - width, top, 81 - width, 18, width, height);
		disableAlpha(.75f);
	}


	public static void enableAlpha(float alpha)
	{
		RenderSystem.enableBlend();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha);
		RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
	}

	public static void disableAlpha(float alpha)
	{
		RenderSystem.disableBlend();
		RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
	}

	public static void onClientTick(TickEvent.ClientTickEvent event)
	{
		if (event.phase != TickEvent.Phase.END)
			return;

		unclampedFlashAlpha += alphaDir * 0.125f;
		if (unclampedFlashAlpha >= 1.5f)
		{
			alphaDir = -1;
		}
		else if (unclampedFlashAlpha <= -0.5f)
		{
			alphaDir = 1;
		}
		flashAlpha = Math.max(0F, Math.min(1F, unclampedFlashAlpha)) * Math.max(0F, Math.min(1F, ModConfig.MAX_HUD_OVERLAY_FLASH_ALPHA.get().floatValue()));
	}

	public static void resetFlash()
	{
		unclampedFlashAlpha = flashAlpha = 0f;
		alphaDir = 1;
	}

	private static void drawSaturationOverlay(HUDOverlayEvent.Saturation event, Player player, float saturationGained, float alpha)
	{
		drawSaturationOverlay(saturationGained, event.saturationLevel, player, event.guiGraphics, event.x, event.y, alpha);
	}

	private static void drawHungerOverlay(HUDOverlayEvent.HungerRestored event, Player player, int hunger, float alpha, boolean useRottenTextures)
	{
		drawHungerOverlay(hunger, event.currentFoodLevel, player, event.guiGraphics, event.x, event.y, alpha, useRottenTextures);
	}

	private static void drawHealthOverlay(HUDOverlayEvent.HealthRestored event, Player player, float alpha)
	{
		drawHealthOverlay(player.getHealth(), event.modifiedHealth, player, event.guiGraphics, event.x, event.y, alpha);
	}

	private static void drawExhaustionOverlay(HUDOverlayEvent.Exhaustion event, Player player, float alpha)
	{
		drawExhaustionOverlay(event.exhaustion, player, event.guiGraphics, event.x, event.y, alpha);
	}

	private static boolean shouldShowEstimatedHealth(Player player)
	{
		// then configuration cancel the render event
		if (!ModConfig.SHOW_FOOD_HEALTH_HUD_OVERLAY.get())
			return false;

		FoodData stats = player.getFoodData();

		// in the `PEACEFUL` mode, health will restore faster
		if (player.level().getDifficulty() == Difficulty.PEACEFUL)
			return false;

		// when player has any changes health amount by any case can't show estimated health
		// because player will confused how much of restored/damaged healths
		if (stats.getFoodLevel() >= 18)
			return false;

		if (player.hasEffect(MobEffects.POISON))
			return false;

		if (player.hasEffect(MobEffects.WITHER))
			return false;

		if (player.hasEffect(MobEffects.REGENERATION))
			return false;

		return true;
	}

	private static void generateHealthBarOffsets(int top, int left, int right, int ticks, Player player)
	{
		// hard code in `InGameHUD`
		random.setSeed((long) (ticks * 312871L));

		final int preferHealthBars = 10;
		final float maxHealth = player.getMaxHealth();
		final float absorptionHealth = (float) Math.ceil(player.getAbsorptionAmount());

		int healthBars = (int) Math.ceil((maxHealth + absorptionHealth) / 2.0F);
		// When maxHealth + absorptionHealth is greater than Integer.INT_MAX,
		// Minecraft will disable heart rendering due to a quirk of MathHelper.ceil.
		// We have a much lower threshold since there's no reason to get the offsets
		// for thousands of hearts.
		// Note: Infinite and > INT_MAX absorption has been seen in the wild.
		// This will effectively disable rendering whenever health is unexpectedly large.
		if (healthBars < 0 || healthBars > 1000) {
			healthBarOffsets.setSize(0);
			return;
		}

		int healthRows = (int) Math.ceil((float) healthBars / 10.0F);

		int healthRowHeight = Math.max(10 - (healthRows - 2), 3);

		boolean shouldAnimatedHealth = false;

		// when some mods using custom render, we need to least provide an option to cancel animation
		if (ModConfig.SHOW_VANILLA_ANIMATION_OVERLAY.get())
		{
			// in vanilla health is too low (below 5) will show heartbeat animation
			// when regeneration will also show heartbeat animation, but we don't need now
			shouldAnimatedHealth = Math.ceil(player.getHealth()) <= 4;
		}

		// adjust the size
		if (healthBarOffsets.size() != healthBars)
			healthBarOffsets.setSize(healthBars);

		// left alignment, multiple rows, reverse
		for (int i = healthBars - 1; i >= 0; --i)
		{
			int row = (int) Math.ceil((float) (i + 1) / (float) preferHealthBars) - 1;
			int x = left + i % preferHealthBars * 8;
			int y = top - row * healthRowHeight;
			// apply the animated offset
			if (shouldAnimatedHealth)
				y += random.nextInt(2);

			// reuse the point object to reduce memory usage
			IntPoint point = healthBarOffsets.get(i);
			if (point == null)
			{
				point = new IntPoint();
				healthBarOffsets.set(i, point);
			}

			point.x = x - left;
			point.y = y - top;
		}
	}

	private static void generateHungerBarOffsets(int top, int left, int right, int ticks, Player player)
	{
		final int preferFoodBars = 10;

		boolean shouldAnimatedFood = false;

		// when some mods using custom render, we need to least provide an option to cancel animation
		if (ModConfig.SHOW_VANILLA_ANIMATION_OVERLAY.get())
		{
			FoodData stats = player.getFoodData();

			// in vanilla saturation level is zero will show hunger animation
			float saturationLevel = stats.getSaturationLevel();
			int foodLevel = stats.getFoodLevel();
			shouldAnimatedFood = saturationLevel <= 0.0F && ticks % (foodLevel * 3 + 1) == 0;
		}

		if (foodBarOffsets.size() != preferFoodBars)
			foodBarOffsets.setSize(preferFoodBars);

		// right alignment, single row
		for (int i = 0; i < preferFoodBars; ++i)
		{
			int x = right - i * 8 - 9;
			int y = top;

			// apply the animated offset
			if (shouldAnimatedFood)
				y += random.nextInt(3) - 1;

			// reuse the point object to reduce memory usage
			IntPoint point = foodBarOffsets.get(i);
			if (point == null)
			{
				point = new IntPoint();
				foodBarOffsets.set(i, point);
			}

			point.x = x - right;
			point.y = y - top;
		}
	}


	public static FoodHelper.QueriedFoodResult queryRenderHeldFood(Player player)
	{
		// try to get the item stack in the player hand
		ItemStack heldItem = player.getMainHandItem();
		FoodHelper.QueriedFoodResult heldFood = FoodHelper.query(heldItem, player);
		boolean canConsume = heldFood != null && FoodHelper.canConsume(player, heldFood.modifiedFoodProperties);
		if (ModConfig.SHOW_FOOD_VALUES_OVERLAY_WHEN_OFFHAND.get() && !canConsume)
		{
			heldItem = player.getOffhandItem();
			heldFood = FoodHelper.query(heldItem, player);
			canConsume = heldFood != null && FoodHelper.canConsume(player, heldFood.modifiedFoodProperties);
		}
		boolean shouldRenderHeldItemValues = !heldItem.isEmpty() && canConsume;
		if (!shouldRenderHeldItemValues)
			return null;

		return heldFood;
	}
}
