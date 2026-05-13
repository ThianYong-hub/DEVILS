package xaero.hud.minimap.radar.icon.cache.id.variant;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.Identifier;
import xaero.common.minimap.render.radar.EntityIconDefinitions;
import xaero.hud.minimap.MinimapLogs;
import xaero.hud.minimap.radar.icon.definition.BuiltInRadarIconDefinitions;
import xaero.hud.minimap.radar.icon.definition.RadarIconDefinition;

public class RadarIconVariantHandler {
   private final StringBuilder legacyEntityStringBuilder = new StringBuilder();
   private final Set<Identifier> brokenBuiltInVariantEntityIds = new HashSet<>();

   public <T extends Entity> Object getEntityVariant(
      RadarIconDefinition iconDefinition, T entity, EntityRenderer<? super T, ?> entityRenderer, EntityRenderState entityRenderState
   ) {
      Object variant = null;
      Identifier entityTexture = null;
      Identifier entityId = EntityType.getId(entity.getType());
      boolean triedBuiltInVariant = false;

      try {
         Identifier entityTextureUnchecked = entityRenderer instanceof LivingEntityRenderer livingEntityRenderer
            ? livingEntityRenderer.getTexture((LivingEntityRenderState)entityRenderState)
            : null;
         entityTexture = entityTextureUnchecked;
      } catch (Throwable var13) {
         MinimapLogs.LOGGER.error("Exception while fetching entity texture to build its variant ID for " + entityId);
         MinimapLogs.LOGGER
            .error(
               "The exception is most likely on another mod's end and suppressing it here could lead to more issues. Please report to appropriate mod devs.",
               var13
            );
      }

      if (iconDefinition != null) {
         Method variantMethod = iconDefinition.getVariantMethod();
         if (variantMethod != null) {
            if (this.isBuiltInVariantMethod(variantMethod)) {
               variant = this.getBuiltInVariantSafely(entityId, entityTexture, entityRenderer, entity);
               triedBuiltInVariant = true;
            } else {
               try {
                  variant = variantMethod.invoke(null, entityTexture, entityRenderer, entity);
               } catch (Throwable var12) {
                  MinimapLogs.LOGGER.error("Exception while using the variant ID method " + iconDefinition.getVariantMethodString() + " defined for " + entityId);
                  MinimapLogs.LOGGER
                     .error(
                        "If the exception is on another mod's end, suppressing it here could lead to more issues. Please report to appropriate mod devs.",
                        var12
                     );
                  iconDefinition.setVariantMethod(null);
               }
            }
         } else {
            variant = this.getLegacyVariantId(iconDefinition, entity, entityRenderer);
         }
      }

      if (variant == null && !triedBuiltInVariant) {
         variant = this.getBuiltInVariantSafely(entityId, entityTexture, entityRenderer, entity);
      }

      return variant;
   }

   private boolean isBuiltInVariantMethod(Method variantMethod) {
      return "getVariant".equals(variantMethod.getName())
         && (variantMethod.getDeclaringClass() == BuiltInRadarIconDefinitions.class
            || variantMethod.getDeclaringClass() == EntityIconDefinitions.class);
   }

   private <T extends Entity> Object getBuiltInVariantSafely(
      Identifier entityId, Identifier entityTexture, EntityRenderer<? super T, ?> entityRenderer, T entity
   ) {
      if (this.brokenBuiltInVariantEntityIds.contains(entityId)) {
         return this.getTextureFallbackVariant(entityTexture);
      }

      try {
         return BuiltInRadarIconDefinitions.getVariant(entityTexture, entityRenderer, entity);
      } catch (Throwable throwable) {
         MinimapLogs.LOGGER.error("Exception while using the built-in variant ID fallback for " + entityId);
         MinimapLogs.LOGGER.error("Falling back to the base entity texture variant to keep the minimap renderer alive.", throwable);
         this.brokenBuiltInVariantEntityIds.add(entityId);
         return this.getTextureFallbackVariant(entityTexture);
      }
   }

   private Object getTextureFallbackVariant(Identifier entityTexture) {
      return entityTexture == null ? "default" : entityTexture;
   }

   private <T extends Entity> String getLegacyVariantId(RadarIconDefinition iconDefinition, T entity, EntityRenderer<? super T, ?> entityRenderer) {
      Method variantIdBuilderMethod = iconDefinition.getVariantIdBuilderMethod();
      if (variantIdBuilderMethod != null && !variantIdBuilderMethod.equals(BuiltInRadarIconDefinitions.BUILD_VARIANT_ID_STRING_METHOD)) {
         this.legacyEntityStringBuilder.setLength(0);

         try {
            variantIdBuilderMethod.invoke(null, this.legacyEntityStringBuilder, entityRenderer, entity);
            return this.legacyEntityStringBuilder.toString();
         } catch (Throwable var8) {
            Identifier entityId = EntityType.getId(entity.getType());
            MinimapLogs.LOGGER
               .error("Exception while using the variant builder ID method " + iconDefinition.getVariantIdBuilderMethodString() + " defined for " + entityId);
            MinimapLogs.LOGGER
               .error("If the exception is on another mod's end, suppressing it here could lead to more issues. Please report to appropriate mod devs.", var8);
            iconDefinition.setVariantIdBuilderMethod(null);
            return null;
         }
      } else {
         Method variantOldIdMethod = iconDefinition.getOldVariantIdMethod();
         if (variantOldIdMethod != null && !variantOldIdMethod.equals(BuiltInRadarIconDefinitions.GET_VARIANT_ID_STRING_METHOD)) {
            try {
               return (String)variantOldIdMethod.invoke(null, entityRenderer, entity);
            } catch (Throwable var9) {
               Identifier entityId = EntityType.getId(entity.getType());
               MinimapLogs.LOGGER
                  .error("Exception while using the variant ID method " + iconDefinition.getOldVariantIdMethodString() + " defined for " + entityId);
               MinimapLogs.LOGGER
                  .error(
                     "If the exception is on another mod's end, suppressing it here could lead to more issues. Please report to appropriate mod devs.", var9
                  );
               iconDefinition.setOldVariantIdMethod(null);
               return null;
            }
         } else {
            return null;
         }
      }
   }
}
