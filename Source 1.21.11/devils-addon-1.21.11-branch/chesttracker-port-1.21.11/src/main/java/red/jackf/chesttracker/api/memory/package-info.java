/**
 * <p>Contains classes for working with Chest Tracker's Memory Banks. Instances of the current memory bank can be obtained
 * from {@link com.example.addon.chesttracker.api.memory.MemoryBankAccess#getLoaded()}.</p>
 *
 * <p>The structure of a Memory Bank has 3 tiers:
 * <ol>
 *     <li>The top level <b>Memory Bank</b> object, containing:</li>
 *     <li>A map of Memory Key IDs (such as minecraft:overworld) to <b>Memory Key</b>s, each of which:</li>
 *     <li>A map of Block Positions to <b>Memory</b> objects</li>
 * </ol>
 * Each Memory contains details such as a list of item stacks, the name (user-defined and ripped), connected positions and
 * more.</p>
 *
 * <h1>TL;DR</h1>
 *
 * <p>If you just want to get a memory from the world and a position, use
 * {@link com.example.addon.chesttracker.api.memory.MemoryBank#getMemory(net.minecraft.world.level.Level, net.minecraft.core.BlockPos)}</p>
 *
 * <p>If you want to get a list of all items in the current world, possibly within a range, use
 * {@link com.example.addon.chesttracker.api.memory.MemoryBank#getCounts(
 *            net.minecraft.resources.Identifier,
 *            com.example.addon.chesttracker.api.memory.counting.CountingPredicate,
 *            com.example.addon.chesttracker.api.memory.counting.StackMergeMode
 * )} along with a desired predicate.</p>
 *
 * @see com.example.addon.chesttracker.api.memory.MemoryBank
 * @see com.example.addon.chesttracker.api.memory.MemoryKey
 * @see com.example.addon.chesttracker.api.memory.Memory
 */
package com.example.addon.chesttracker.api.memory;