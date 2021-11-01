/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package graphics.kiln.bakedminecraftmodels.gl;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import graphics.kiln.bakedminecraftmodels.BakedMinecraftModels;
import graphics.kiln.bakedminecraftmodels.data.InstanceBatch;
import graphics.kiln.bakedminecraftmodels.debug.DebugInfo;
import graphics.kiln.bakedminecraftmodels.mixin.buffer.VertexBufferAccessor;
import graphics.kiln.bakedminecraftmodels.mixin.renderlayer.MultiPhaseParametersAccessor;
import graphics.kiln.bakedminecraftmodels.mixin.renderlayer.MultiPhaseRenderPassAccessor;
import graphics.kiln.bakedminecraftmodels.mixin.renderlayer.RenderPhaseAccessor;
import graphics.kiln.bakedminecraftmodels.model.GlobalModelUtils;
import graphics.kiln.bakedminecraftmodels.model.InstancedRenderDispatcher;
import graphics.kiln.bakedminecraftmodels.model.VboBackedModel;
import graphics.kiln.bakedminecraftmodels.ssbo.SectionedPersistentBuffer;
import graphics.kiln.bakedminecraftmodels.ssbo.SectionedSyncObjects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.Shader;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.util.Window;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

public class GlSsboRenderDispacher implements InstancedRenderDispatcher {

    public static final int BUFFER_CREATION_FLAGS = GL30C.GL_MAP_WRITE_BIT | ARBBufferStorage.GL_MAP_PERSISTENT_BIT;
    public static final int BUFFER_MAP_FLAGS = GL30C.GL_MAP_WRITE_BIT | GL30C.GL_MAP_FLUSH_EXPLICIT_BIT | ARBBufferStorage.GL_MAP_PERSISTENT_BIT;
    public static final int BUFFER_SECTIONS = 3;
    public static final long PART_PBO_SIZE = 9175040L; // 8.75 MiB
    public static final long MODEL_PBO_SIZE = 524288L; // 500 KiB
    public static final long TRANSLUCENT_EBO_SIZE = 512 * 1024; // 512 KiB - TODO figure out what a reasonable value is

    public final SectionedPersistentBuffer partPersistentSsbo;
    public final SectionedPersistentBuffer modelPersistentSsbo;
    public final SectionedPersistentBuffer translucencyPersistentEbo;
    public final SectionedSyncObjects syncObjects;

    public GlSsboRenderDispacher() {
        partPersistentSsbo = createPersistentBuffer(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, PART_PBO_SIZE);
        modelPersistentSsbo = createPersistentBuffer(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, MODEL_PBO_SIZE);
        translucencyPersistentEbo = createPersistentBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, TRANSLUCENT_EBO_SIZE);
        syncObjects = new SectionedSyncObjects(BUFFER_SECTIONS);
    }

    private static SectionedPersistentBuffer createPersistentBuffer(int bufferType, long ssboSize) {
        int name = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(bufferType, name);
        long fullSize = ssboSize * BUFFER_SECTIONS;
        ARBBufferStorage.nglBufferStorage(bufferType, fullSize, MemoryUtil.NULL, BUFFER_CREATION_FLAGS);
        return new SectionedPersistentBuffer(
                GL30C.nglMapBufferRange(bufferType, 0, fullSize, BUFFER_MAP_FLAGS),
                name,
                BUFFER_SECTIONS,
                ssboSize
        );
    }

    public void renderQueues() {
        if (!GlobalModelUtils.bakingData.isEmptyShallow()) {

            long currentPartSyncObject = syncObjects.getCurrentSyncObject();

            if (currentPartSyncObject != MemoryUtil.NULL) {
                int waitResult = GL32C.glClientWaitSync(currentPartSyncObject, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, 10000000); // 10 seconds
                if (waitResult == GL32C.GL_WAIT_FAILED || waitResult == GL32C.GL_TIMEOUT_EXPIRED) {
                    BakedMinecraftModels.LOGGER.error("OpenGL sync failed");
                }
            }

            GlobalModelUtils.bakingData.writeData();

            long partSectionStartPos = partPersistentSsbo.getCurrentSection() * partPersistentSsbo.getSectionSize();
            long modelSectionStartPos = modelPersistentSsbo.getCurrentSection() * modelPersistentSsbo.getSectionSize();
            long translucencySectionStartPos = translucencyPersistentEbo.getCurrentSection() * translucencyPersistentEbo.getSectionSize();
            long partLength = partPersistentSsbo.getPositionOffset().getAcquire();
            long modelLength = modelPersistentSsbo.getPositionOffset().getAcquire();
            long translucencyLength = translucencyPersistentEbo.getPositionOffset().getAcquire();

            GlStateManager._glBindBuffer(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, partPersistentSsbo.getName());
            GL30C.glFlushMappedBufferRange(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, partSectionStartPos, partLength);

            GlStateManager._glBindBuffer(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, modelPersistentSsbo.getName());
            GL30C.glFlushMappedBufferRange(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, modelSectionStartPos, modelLength);

            GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, translucencyPersistentEbo.getName());
            GL30C.glFlushMappedBufferRange(GL15.GL_ELEMENT_ARRAY_BUFFER, translucencySectionStartPos, translucencyLength);

            if (currentPartSyncObject != MemoryUtil.NULL) {
                GL32C.glDeleteSync(currentPartSyncObject);
            }
            syncObjects.setCurrentSyncObject(GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0));

            GL30C.glBindBufferRange(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, 1, partPersistentSsbo.getName(), partSectionStartPos, partLength);
            GL30C.glBindBufferRange(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, 2, modelPersistentSsbo.getName(), modelSectionStartPos, modelLength);

            DebugInfo.currentPartBufferSize = partLength;
            DebugInfo.currentModelBufferSize = modelLength;
            DebugInfo.currentTranslucencyEboSize = translucencyLength;
            partPersistentSsbo.nextSection();
            modelPersistentSsbo.nextSection();
            translucencyPersistentEbo.nextSection();
            syncObjects.nextSection();

            int instanceOffset = 0;

            RenderLayer currentRenderLayer = null;
            VertexBuffer currentVertexBuffer = null;
            BufferRenderer.unbindAll();

            for (Map<RenderLayer, Map<VboBackedModel, InstanceBatch>> perOrderedSectionData : GlobalModelUtils.bakingData) {

                for (Map.Entry<RenderLayer, Map<VboBackedModel, InstanceBatch>> perRenderLayerData : perOrderedSectionData.entrySet()) {
                    RenderLayer nextRenderLayer = perRenderLayerData.getKey();
                    if (currentRenderLayer == null) {
                        currentRenderLayer = nextRenderLayer;
                        currentRenderLayer.startDrawing();
                    } else if (!currentRenderLayer.equals(nextRenderLayer)) {
                        currentRenderLayer.endDrawing();
                        currentRenderLayer = nextRenderLayer;
                        currentRenderLayer.startDrawing();
                    }

                    Shader shader = RenderSystem.getShader();

                    //noinspection ConstantConditions
                    MultiPhaseParametersAccessor multiPhaseParameters = (MultiPhaseParametersAccessor) (Object) ((MultiPhaseRenderPassAccessor) nextRenderLayer).getPhases();

                    for (Map.Entry<VboBackedModel, InstanceBatch> perModelData : perRenderLayerData.getValue().entrySet()) {
                        VertexBuffer nextVertexBuffer = perModelData.getKey().getBakedVertices();
                        VertexBufferAccessor vertexBufferAccessor = (VertexBufferAccessor) nextVertexBuffer;
                        int vertexCount = vertexBufferAccessor.getVertexCount();
                        if (vertexCount <= 0) continue;
                        boolean requiresIndexing = requiresIndexing(multiPhaseParameters);

                        if (currentVertexBuffer == null) {
                            currentVertexBuffer = nextVertexBuffer;
                            vertexBufferAccessor.invokeBindVertexArray();
                            vertexBufferAccessor.invokeBind();
                            if (requiresIndexing) GL30C.glBindBufferBase(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, 3, vertexBufferAccessor.getVertexBufferId());
                            currentVertexBuffer.getElementFormat().startDrawing();
                        } else if (!currentVertexBuffer.equals(nextVertexBuffer)) {
                            currentVertexBuffer.getElementFormat().endDrawing();
                            currentVertexBuffer = nextVertexBuffer;
                            vertexBufferAccessor.invokeBindVertexArray();
                            vertexBufferAccessor.invokeBind();
                            if (requiresIndexing) GL30C.glBindBufferBase(ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER, 3, vertexBufferAccessor.getVertexBufferId());
                            currentVertexBuffer.getElementFormat().startDrawing();
                        }

                        VertexFormat.DrawMode drawMode = vertexBufferAccessor.getDrawMode();
                        InstanceBatch instanceBatch = perModelData.getValue();
                        int instanceCount = instanceBatch.size();
                        if (instanceCount <= 0) continue;

                        for (int i = 0; i < 12; ++i) {
                            int j = RenderSystem.getShaderTexture(i);
                            shader.addSampler("Sampler" + i, j);
                        }

                        if (shader.projectionMat != null) {
                            shader.projectionMat.set(RenderSystem.getProjectionMatrix());
                        }

                        if (shader.colorModulator != null) {
                            shader.colorModulator.set(RenderSystem.getShaderColor());
                        }

                        if (shader.fogStart != null) {
                            shader.fogStart.set(RenderSystem.getShaderFogStart());
                        }

                        if (shader.fogEnd != null) {
                            shader.fogEnd.set(RenderSystem.getShaderFogEnd());
                        }

                        if (shader.fogColor != null) {
                            shader.fogColor.set(RenderSystem.getShaderFogColor());
                        }

                        if (shader.textureMat != null) {
                            shader.textureMat.set(RenderSystem.getTextureMatrix());
                        }

                        if (shader.gameTime != null) {
                            shader.gameTime.set(RenderSystem.getShaderGameTime());
                        }

                        if (shader.screenSize != null) {
                            Window window = MinecraftClient.getInstance().getWindow();
                            shader.screenSize.set((float) window.getFramebufferWidth(), (float) window.getFramebufferHeight());
                        }

                        if (shader.lineWidth != null && (drawMode == VertexFormat.DrawMode.LINES || drawMode == VertexFormat.DrawMode.LINE_STRIP)) {
                            shader.lineWidth.set(RenderSystem.getShaderLineWidth());
                        }

                        // we have to manually get it from the shader every time because different shaders have different uniform objects for the same uniform.
                        GlUniform instanceOffsetUniform = shader.getUniform("InstanceOffset");
                        if (instanceOffsetUniform != null) {
                            instanceOffsetUniform.set(instanceOffset);
                        }

                        if (requiresIndexing) {
                            drawSortedFakeInstanced(instanceBatch, shader, vertexBufferAccessor);
                        } else {
                            RenderSystem.setupShaderLights(shader);
                            shader.bind();
                            GL31C.glDrawElementsInstanced(drawMode.mode, vertexCount, vertexBufferAccessor.getVertexFormat().count, MemoryUtil.NULL, instanceCount);
                            shader.unbind();
                        }

                        instanceOffset += instanceCount;

                        DebugInfo.ModelDebugInfo currentDebugInfo = DebugInfo.modelToDebugInfoMap.computeIfAbsent(perModelData.getKey().getClass().getSimpleName(), (ignored) -> new DebugInfo.ModelDebugInfo());
                        currentDebugInfo.instances += instanceCount;
                        currentDebugInfo.sets++;
                        
                        GlobalModelUtils.bakingData.recycleInstanceBatch(instanceBatch);
                    }
                }
            }

            if (currentVertexBuffer != null) {
                currentVertexBuffer.getElementFormat().endDrawing();
            }

            if (currentRenderLayer != null) {
                currentRenderLayer.endDrawing();
            }

            GlobalModelUtils.bakingData.reset();
        }
    }

    /**
     * Do a 'fake' glDrawElementsInstanced call, with vertex sorting.
     * <p>
     * This builds a EBO containing all the vertices for all the elements to draw. The notable
     * thing here is that we have control of the draw order - we can sort the elements by depth
     * from the camera, and use this to batch the rendering of transparent objects.
     */
    private void drawSortedFakeInstanced(InstanceBatch batch, Shader shader, VertexBufferAccessor vba) {
        int instanceCount = batch.size();
        int indexCount = vba.getVertexCount();
        VertexFormat.DrawMode drawMode = vba.getDrawMode();

        GlUniform countUniform = shader.getUniform("InstanceVertCount");
        if (countUniform != null) {
            countUniform.set(indexCount);
        }

        // this needs to be re-bound because normal instanced stuff will probably be rendered before this
        GlStateManager._glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, translucencyPersistentEbo.getName());

        // TODO do we need to disable the VAO here? It's not bound in the shader, can it still
        //  cause issues?
        // (from burger) probably not

        RenderSystem.setupShaderLights(shader);
        shader.bind();
        GL31C.glDrawElements(drawMode.mode, indexCount * instanceCount, batch.getIndexType().count, batch.getIndexOffset());
        shader.unbind();

        // TODO Unbind EBO?
        // (from burger) the next thing that comes across will replace the binding, so it's probably not needed
    }

    // TODO: move this somewhere else
    public static boolean requiresIndexing(MultiPhaseParametersAccessor multiPhaseParameters) {
        // instanced: opaque and additive with depth write off
        // index buffer: everything else
        String transparencyName = multiPhaseParameters.getTransparency().toString();
        //noinspection ConstantConditions
        return !transparencyName.equals("no_transparency") && !(transparencyName.equals("additive_transparency") && multiPhaseParameters.getWriteMaskState().equals(RenderPhaseAccessor.getColorMask()));
    }
}
