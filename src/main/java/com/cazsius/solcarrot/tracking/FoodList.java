package com.cazsius.solcarrot.tracking;

import com.cazsius.solcarrot.SOLCarrotConfig;
import com.cazsius.solcarrot.api.FoodCapability;
import com.cazsius.solcarrot.api.SOLCarrotAPI;
import com.cazsius.solcarrot.client.FoodItems;
import com.cazsius.solcarrot.data.FoodPenalties;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Queue;

@ParametersAreNonnullByDefault
public final class FoodList implements FoodCapability {
	private static final String NBT_KEY_FOOD_LIST = "foodList";
	private static final String NBT_KEY_DIVERSITY_HISTORY = "diversityHistory";
	private static final String NBT_KEY_HISTORY_FOOD_COUNT = "historyFoodCount";
	
	public static FoodList get(Player player) {
		return (FoodList) player.getCapability(SOLCarrotAPI.foodCapability)
			.orElseThrow(FoodListNotFoundException::new);
	}
	
	private final Set<FoodInstance> foods = new HashSet<>();
	private final Queue<FoodInstance> diversityHistory = new LinkedList<>();
	private final Map<FoodInstance, Integer> historyFoodCount = new HashMap<>();
	
	@Nullable
	private ProgressInfo cachedProgressInfo;
	
	public FoodList() {}
	
	private final LazyOptional<FoodList> capabilityOptional = LazyOptional.of(() -> this);
	
	@Override
	public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
		return capability == SOLCarrotAPI.foodCapability ? capabilityOptional.cast() : LazyOptional.empty();
	}
	
	/** used for persistent storage */
	@Override
	public CompoundTag serializeNBT() {
		var tag = new CompoundTag();
		
		var list = new ListTag();
		foods.stream()
			.map(FoodInstance::encode)
			.filter(Objects::nonNull)
			.map(StringTag::valueOf)
			.forEach(list::add);
		tag.put(NBT_KEY_FOOD_LIST, list);
		
		var queue = new ListTag();
		diversityHistory.stream()
			.map(FoodInstance::encode)
			.filter(Objects::nonNull)
			.map(StringTag::valueOf)
			.forEach(queue::add);
		tag.put(NBT_KEY_DIVERSITY_HISTORY, queue);
		
		var map = new CompoundTag();
		historyFoodCount.entrySet().stream()
			.map(entry -> Map.entry(entry.getKey().encode(), entry.getValue()))
			.filter(Objects::nonNull)
			.forEach(entry -> map.putInt(entry.getKey(), entry.getValue()));
		tag.put(NBT_KEY_HISTORY_FOOD_COUNT, map);
		
		return tag;
	}
	
	/** used for persistent storage */
	@Override
	public void deserializeNBT(CompoundTag tag) {
		var list = tag.getList(NBT_KEY_FOOD_LIST, Tag.TAG_STRING);
		
		foods.clear();
		list.stream()
			.map(nbt -> (StringTag) nbt)
			.map(StringTag::getAsString)
			.map(FoodInstance::decode)
			.filter(Objects::nonNull)
			.forEach(foods::add);
		
		var queue = tag.getList(NBT_KEY_DIVERSITY_HISTORY, Tag.TAG_STRING);

    diversityHistory.clear();
    queue.stream()
			.map(nbt -> (StringTag) nbt)
			.map(StringTag::getAsString)
			.map(FoodInstance::decode)
			.filter(Objects::nonNull)
			.forEach(diversityHistory::offer);
		
		var map = tag.getCompound(NBT_KEY_HISTORY_FOOD_COUNT);
		
    historyFoodCount.clear();
    map.getAllKeys().forEach(key -> {
        var foodInstance = FoodInstance.decode(key);
        if (foodInstance != null) {
            historyFoodCount.put(foodInstance, map.getInt(key));
        }
    });
		
		invalidateProgressInfo();
	}
	
	/** @return true if the food was not previously known, i.e. if a new food has been tried */
	public boolean addFood(Item food) {
		FoodInstance foodInstance = new FoodInstance(food);
		
		boolean wasAdded = foods.add(foodInstance) && SOLCarrotConfig.shouldCount(food);
		invalidateProgressInfo();

		int diversityHistorySize = SOLCarrotConfig.getDiversityHistorySize();
		if (diversityHistorySize > 0) {
			if (diversityHistory.size() == diversityHistorySize) {
				FoodInstance dequeuedFoodInstance = diversityHistory.poll();
				int historyInstancesRemaining = historyFoodCount.get(dequeuedFoodInstance)-1;
				
				if (historyInstancesRemaining == 0) {
					historyFoodCount.remove(dequeuedFoodInstance);
				} else {
					historyFoodCount.put(dequeuedFoodInstance, historyInstancesRemaining);
				}
			}

			int historyInstancesUpdated = historyFoodCount.getOrDefault(foodInstance, 0)+1;
			historyFoodCount.put(foodInstance, historyInstancesUpdated);
			diversityHistory.offer(foodInstance);
		}
		
		return wasAdded;
	}
	
	@Override
	public boolean hasEaten(Item food) {
		if (!food.isEdible()) return false;
		return foods.contains(new FoodInstance(food));
	}
	
	public void clearFood() {
		foods.clear();
		diversityHistory.clear();
		historyFoodCount.clear();
		invalidateProgressInfo();
	}
	
	public Set<FoodInstance> getEatenFoods() {
		return new HashSet<>(foods);
	}
	
	// TODO: is this actually desirable? it doesn't filter at all
	@Override
	public int getEatenFoodCount() {
		return foods.size();
	}
	
	public ProgressInfo getProgressInfo() {
		if (cachedProgressInfo == null) {
			cachedProgressInfo = new ProgressInfo(this);
		}
		return cachedProgressInfo;
	}
	
	public void invalidateProgressInfo() {
		cachedProgressInfo = null;
	}
	
	public static class FoodListNotFoundException extends RuntimeException {
		public FoodListNotFoundException() {
			super("Player must have food capability attached, but none was found.");
		}
	}
	
	private float getDiminishingReturnsPenalty(Item food) {
		if (!SOLCarrotConfig.shouldCount(food)) return 1;
		
		FoodInstance foodInstance = new FoodInstance(food);
		int timesPreviouslyEaten = historyFoodCount.getOrDefault(foodInstance, 0);

		float foodSpecificRate = FoodItems.getFoodNutritionDecayRate(foodInstance);
		return (float) (1-Math.exp(-foodSpecificRate*timesPreviouslyEaten));
	}
	
	public FoodPenalties getFoodPenalties(ItemStack stack) {
		Item item = stack.getItem();
		
		FoodProperties foodProperties = item.getFoodProperties(stack, null);
		int nutrition = foodProperties.getNutrition();
		float saturation = foodProperties.getSaturationModifier();
		
		float diminishingReturnsPenalty = getDiminishingReturnsPenalty(item);
		int nutritionPenalty = (int) (nutrition < 0 ? Math.floor(diminishingReturnsPenalty * nutrition) : Math.ceil(diminishingReturnsPenalty * nutrition));
		float saturationPenalty = (float) (nutritionPenalty == 0 ? 0 : diminishingReturnsPenalty * saturation / (2 * nutritionPenalty));

		return new FoodPenalties(nutritionPenalty, saturationPenalty);
	}
}
