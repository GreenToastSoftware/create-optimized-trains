/*
 * Forge CoreMod: Fix for Create issue #8727
 * "Stained/Tinted glass on trains rendering issue"
 *
 * Problem:
 *   Flywheel's OitFramebuffer.composite() uses depthMask(true) + depthFunc(GL_ALWAYS).
 *   This overwrites the depth buffer with glass front-surface depth for every glass pixel.
 *   Anything that renders AFTER composite (water, translucent terrain, Sheet-type block
 *   entities) fails the depth test and becomes invisible behind glass.
 *
 * Fix:
 *   Change composite()'s depthMask(true) to depthMask(false).
 *   Color blending (glass tinting) is unaffected.
 *   renderDepthFromTransmittance() is left untouched — it correctly handles
 *   depth writes only for effectively-opaque glass (transmittance → 0).
 *
 * Target class: dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer
 * Target method: composite()V
 */

var ASMAPI = Java.type('net.minecraftforge.coremod.api.ASMAPI');
var Opcodes = Java.type('org.objectweb.asm.Opcodes');
var InsnNode = Java.type('org.objectweb.asm.tree.InsnNode');
var MethodInsnNode = Java.type('org.objectweb.asm.tree.MethodInsnNode');

function initializeCoreMod() {
    return {
        'oit_composite_no_depth_write': {
            'target': {
                'type': 'CLASS',
                'name': 'dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer'
            },
            'transformer': function (classNode) {
                var compositeMethod = null;
                for (var i = 0; i < classNode.methods.size(); i++) {
                    var m = classNode.methods.get(i);
                    if (m.name === 'composite' && m.desc === '()V') {
                        compositeMethod = m;
                        break;
                    }
                }

                if (compositeMethod === null) {
                    ASMAPI.log('WARN', '[CreateOptimizedTrains] OitFramebuffer.composite() not found!');
                    return classNode;
                }

                // Find the first ICONST_1 followed by INVOKESTATIC depthMask(Z)V
                // In the bytecode:
                //   32: iconst_1                  // push true
                //   33: invokestatic #169          // RenderSystem.depthMask(boolean)
                // We change iconst_1 → iconst_0 to push false instead.
                var insns = compositeMethod.instructions;
                var patched = false;
                for (var j = 0; j < insns.size() - 1; j++) {
                    var insn = insns.get(j);
                    if (insn.getOpcode() === Opcodes.ICONST_1) {
                        var next = insn.getNext();
                        if (next !== null &&
                            next.getOpcode() === Opcodes.INVOKESTATIC &&
                            next.name === 'depthMask' &&
                            next.desc === '(Z)V') {
                            // Replace ICONST_1 (opcode 4) with ICONST_0 (opcode 3)
                            insns.set(insn, new InsnNode(Opcodes.ICONST_0));
                            patched = true;
                            ASMAPI.log('INFO', '[CreateOptimizedTrains] Patched OitFramebuffer.composite() — depthMask(true) → depthMask(false)');
                            break;
                        }
                    }
                }

                if (!patched) {
                    ASMAPI.log('WARN', '[CreateOptimizedTrains] Could not find depthMask(true) in composite()!');
                }

                return classNode;
            }
        }
    };
}
