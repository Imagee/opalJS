/* BSD 2-Clause License - see OPAL/LICENSE for details. */
package org.opalj.ll.llvm.value

import org.bytedeco.llvm.LLVM.LLVMValueRef
import org.bytedeco.llvm.global.LLVM.{LLVMGetParamParent, LLVMGetValueKind, LLVMArgumentValueKind}
import org.opalj.ll.llvm.value

case class Argument(ref: LLVMValueRef) extends Value(ref) {
    assert(LLVMGetValueKind(ref) == LLVMArgumentValueKind, "ref has to be an argument")

    def parent(): value.Function = value.Function(LLVMGetParamParent(ref))
}