package dev.vexor.radium.culling;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Set;

import com.logisticscraft.occlusionculling.OcclusionCullingInstance;

import com.logisticscraft.occlusionculling.util.Vec3d;
import dev.vexor.radium.culling.access.Cullable;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3i;

public class CullTask implements Runnable {

	public boolean requestCull = false;

	private final OcclusionCullingInstance culling;
    private final MinecraftClient client = MinecraftClient.getInstance();
	private final int sleepDelay = SodiumClientMod.options().culling.sleepDelay;
	private final int hitboxLimit = SodiumClientMod.options().culling.hitboxLimit;
	private final Set<String> unCullable;
	public long lastTime = 0;
	
	// reused preallocated vars
	private Vec3d lastPos = new Vec3d(0, 0, 0);
	private Vec3d aabbMin = new Vec3d(0, 0, 0);
	private Vec3d aabbMax = new Vec3d(0, 0, 0);

	public CullTask(OcclusionCullingInstance culling, Set<String> unCullable) {
		this.culling = culling;
		this.unCullable = unCullable;
	}
	
	@Override
	public void run() {
		while (client.running) {
			try {
				Thread.sleep(sleepDelay);

				if (RadiumEntityCulling.enabled && client.world != null && client.player != null && client.player.ticksAlive > 10 && client.getCameraEntity() != null) {
                    net.minecraft.util.math.Vec3d cameraMC = client.player.getCameraPosVec(0);
					if (requestCull || !(cameraMC.x == lastPos.x && cameraMC.y == lastPos.y && cameraMC.z == lastPos.z)) {
						long start = System.currentTimeMillis();
						requestCull = false;
						lastPos.set(cameraMC.x, cameraMC.y, cameraMC.z);
						Vec3d camera = lastPos;
						culling.resetCache();
						boolean noCulling = client.player.isSpectator() || client.options.perspective != 0;
						Iterator<BlockEntity> iterator = client.world.blockEntities.iterator();
                        BlockEntity entry;
						while(iterator.hasNext()) {
							try {
								entry = iterator.next();
							}catch(NullPointerException | ConcurrentModificationException ex) {
								break; // We are not synced to the main thread, so NPE's/CME are allowed here and way less
								// overhead probably than trying to sync stuff up for no really good reason
							}
							if(unCullable.contains(entry.getBlock().getTranslationKey())) {
								continue;
							}
							Cullable cullable = (Cullable) entry;
							if (!cullable.isForcedVisible()) {
								if (noCulling) {
									cullable.setCulled(false);
									continue;
								}
								BlockPos pos = entry.getPos();
								if(pos.getSquaredDistance(new Vec3i(cameraMC.x, cameraMC.y, cameraMC.z)) < 64 * 64) { // 64 is the fixed max tile view distance
								    aabbMin.set(pos.getX(), pos.getY(), pos.getZ());
								    aabbMax.set(pos.getX()+1d, pos.getY()+1d, pos.getZ()+1d);
									boolean visible = culling.isAABBVisible(aabbMin, aabbMax, camera);
									cullable.setCulled(!visible);
								}

							}
						}
						Entity entity = null;
						Iterator<Entity> iterable = client.world.entities.iterator();
						while (iterable.hasNext()) {
							try {
								entity = iterable.next();
							} catch (NullPointerException | ConcurrentModificationException ex) {
								break; // We are not synced to the main thread, so NPE's/CME are allowed here and way less
										// overhead probably than trying to sync stuff up for no really good reason
							}
							if(entity == null || !(entity instanceof Cullable)) {
							    continue; // Not sure how this could happen outside from mixin screwing up the inject into Entity
							}
							Cullable cullable = (Cullable) entity;
							if (!cullable.isForcedVisible()) {
								if (noCulling || isSkippableArmorstand(entity)) {
									cullable.setCulled(false);
									continue;
								}
							    if(entity.getPos().squaredDistanceTo(cameraMC) > SodiumClientMod.options().culling.tracingDistance * SodiumClientMod.options().culling.tracingDistance) {
							        cullable.setCulled(false); // If your entity view distance is larger than tracingDistance just render it
							        continue;
							    }
							    Box boundingBox = entity.getBoundingBox();
							    if(boundingBox.maxX - boundingBox.minX > hitboxLimit || boundingBox.maxY - boundingBox.minY > hitboxLimit || boundingBox.maxZ - boundingBox.minZ > hitboxLimit) {
								    cullable.setCulled(false); // To big to bother to cull
								    continue;
								}
							    aabbMin.set(boundingBox.minX, boundingBox.minY, boundingBox.minZ);
							    aabbMax.set(boundingBox.maxX, boundingBox.maxY, boundingBox.maxZ);
								boolean visible = culling.isAABBVisible(aabbMin, aabbMax, camera);
								cullable.setCulled(!visible);
							}
						}
						lastTime = (System.currentTimeMillis()-start);
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		SodiumClientMod.logger().warn("Shutting down culling task!");
	}
	
	private boolean isSkippableArmorstand(Entity entity) {
	    if(!SodiumClientMod.options().culling.skipMarkerArmorStands) return false;
	    return entity instanceof ArmorStandEntity && ((ArmorStandEntity) entity).shouldShowName();
	}
}
