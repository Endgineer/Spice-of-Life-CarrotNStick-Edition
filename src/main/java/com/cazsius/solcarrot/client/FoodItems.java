package com.cazsius.solcarrot.client;

import com.cazsius.solcarrot.SOLCarrot;
import com.cazsius.solcarrot.SOLCarrotConfig;
import com.cazsius.solcarrot.json.JsonHandler;
import com.cazsius.solcarrot.tracking.FoodInstance;
import com.google.common.collect.Maps;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, modid = SOLCarrot.MOD_ID, bus = MOD)
public final class FoodItems {
	private static List<Item> foodsBeforeBlacklist;
	private static List<Item> foods;
	private static boolean isConfigLoaded = false;
	
	private static final String FOOD_NUTRITION_DECAY_FILE_NAME = SOLCarrot.MOD_ID+"-food_nutrition_decays.json";
	private static Map<String, Float> foodNutritionDecays;
	private static final TreeMap<String, Float> defaultFoodNutritionDecays = Maps.newTreeMap();
	
	public static float getFoodNutritionDecayRate(FoodInstance food) {
		if (foodNutritionDecays == null) {
			foodNutritionDecays = JsonHandler.getOrCreateConfigFile(FMLPaths.CONFIGDIR.get().toFile(), FOOD_NUTRITION_DECAY_FILE_NAME, defaultFoodNutritionDecays, new TypeToken<TreeMap<String, Float>>(){}.getType());
		}
		
		return foodNutritionDecays.get(food.encode());
	}
	
	/** @return a list of all item stacks that can be eaten, including blacklisted/hidden ones */
	public static List<Item> getAllFoodsIgnoringBlacklist() {
		return new ArrayList<>(foodsBeforeBlacklist);
	}
	
	/** @return a list of all item stacks that can be eaten */
	public static List<Item> getAllFoods() {
		return new ArrayList<>(foods);
	}
	
	@SubscribeEvent
	public static void setUp(FMLLoadCompleteEvent event) {
		foodsBeforeBlacklist = ForgeRegistries.ITEMS.getValues().stream()
			.filter(Item::isEdible)
			// sort by name
			.sorted(Comparator.comparing(food -> I18n.get(food.getDescriptionId() + ".name")))
			.collect(Collectors.toList());
		
		// depending on the other mods involved, config might be loaded before or after this event
		tryApplyBlacklist();
	}
	
	@SubscribeEvent
	public static void onConfigUpdate(ModConfigEvent event) {
		if (event.getConfig().getType() == ModConfig.Type.CLIENT) return;
		
		isConfigLoaded = true;
		
		tryApplyBlacklist();
	}
	
	private static void tryApplyBlacklist() {
		if (foodsBeforeBlacklist == null) return;
		if (!isConfigLoaded) return;
		
		foods = foodsBeforeBlacklist.stream()
			.filter(SOLCarrotConfig::isAllowed)
			.collect(Collectors.toList());

		defaultFoodNutritionDecays.putAll(FoodItems.getAllFoods().stream().collect(Collectors.toMap(item -> new FoodInstance(item).encode(), item -> {
			FoodProperties foodProperties = item.getFoodProperties(item.getDefaultInstance(), null);
			return (float) (1.0 / (1.0 + Math.log10(1.0 + foodProperties.getNutrition())));
		})));
		
		getFoodNutritionDecayRate(new FoodInstance(Items.CARROT));
	}
}
