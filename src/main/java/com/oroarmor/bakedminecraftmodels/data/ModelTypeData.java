/*
 * MIT License
 *
 * Copyright (c) 2021 OroArmor (Eli Orona), Blaze4D
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.oroarmor.bakedminecraftmodels.data;

import com.oroarmor.bakedminecraftmodels.ssbo.SectionedPbo;
import it.unimi.dsi.fastutil.PriorityQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.nio.ByteBuffer;
import java.util.List;

public class ModelTypeData {

    private final PriorityQueue<ModelInstanceData> modelInstancePool;
    private final List<ModelInstanceData> modelInstanceList;
    private ModelInstanceData currentModelInstanceData;

    public ModelTypeData(PriorityQueue<ModelInstanceData> modelInstancePool) {
        this.modelInstancePool = modelInstancePool;
        this.modelInstanceList = new ObjectArrayList<>(64);
    }

    private ModelInstanceData getModelInstanceData() {
        if (!modelInstancePool.isEmpty()) {
            ModelInstanceData pooledObj = modelInstancePool.dequeue();
            pooledObj.reset();
            return pooledObj;
        } else {
            return new ModelInstanceData();
        }
    }

    public void createCurrentModelInstanceData() {
        currentModelInstanceData = getModelInstanceData();
        modelInstanceList.add(currentModelInstanceData);
    }

    public ModelInstanceData getCurrentModelInstanceData() {
        return currentModelInstanceData;
    }

    public int getInstanceCount() {
        return modelInstanceList.size();
    }

    public void reset() {
        for(ModelInstanceData modelInstanceData : modelInstanceList) {
            modelInstancePool.enqueue(modelInstanceData);
        }
        modelInstanceList.clear();
        currentModelInstanceData = null;
    }

    public void writeToPbos(SectionedPbo modelPbo, SectionedPbo partPbo) {
        for (ModelInstanceData modelInstanceData : modelInstanceList) {
            modelInstanceData.writeToPbos(modelPbo, partPbo);
        }
    }

}