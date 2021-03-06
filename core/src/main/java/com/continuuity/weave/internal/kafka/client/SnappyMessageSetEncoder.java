/*
 * Copyright 2012-2013 Continuuity,Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.continuuity.weave.internal.kafka.client;

import org.xerial.snappy.SnappyOutputStream;

import java.io.IOException;
import java.io.OutputStream;

/**
 * A {@link MessageSetEncoder} that compress messages using snappy.
 */
final class SnappyMessageSetEncoder extends AbstractCompressedMessageSetEncoder {

  SnappyMessageSetEncoder() {
    super(Compression.SNAPPY);
  }

  @Override
  protected OutputStream createCompressedStream(OutputStream os) throws IOException {
    return new SnappyOutputStream(os);
  }
}
