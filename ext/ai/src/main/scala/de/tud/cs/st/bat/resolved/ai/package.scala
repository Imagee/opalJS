/* License (BSD Style License):
 * Copyright (c) 2009 - 2013
 * Software Technology Group
 * Department of Computer Science
 * Technische Universität Darmstadt
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 *  - Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *  - Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *  - Neither the name of the Software Technology Group or Technische
 *    Universität Darmstadt nor the names of its contributors may be used to
 *    endorse or promote products derived from this software without specific
 *    prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package de.tud.cs.st
package bat
package resolved

/**
 * This package defines classes and traits used by BAT's abstract interpretation
 * framework – called BATAI in the following.
 *
 * @note This framework assumes that the analyzed bytecode is valid; i.e., the JVM's
 * bytecode verifier would verify the code. Furthermore, load-time errors
 * (e.g., `LinkageErrors`) are completely ignored to facilitate the analysis of
 * parts of a project. In general, if the presented bytecode is not valid,
 * the result is undefined (i.e., BATAI may report meaningless results, crash or run
 * indefinitely).
 *
 * @see [[de.tud.cs.st.bat.resolved.ai.AI]] - the main class
 *
 * @author Michael Eichberg
 */
package object ai {

    /**
     * Exception that is thrown if the framework identifies an error in the concrete
     * implementation of a specific domain. I.e., the error is related to an error in
     * a user's implementation of a domain.
     */
    class AIImplementationError(message: String) extends RuntimeException(message)

    /**
     * Creates and immediately throws an `AIImplementationError`.
     */
    @throws[AIImplementationError] def AIImplementationError(message: String): Nothing = {
        throw new AIImplementationError(message)
    }

}
