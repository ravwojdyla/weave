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
package com.continuuity.weave.filesystem;

import com.google.common.base.Throwables;

import java.io.IOException;
import java.net.URI;

/**
 * Providers helper methods for creating different {@link LocationFactory}.
 */
public final class LocationFactories {

  /**
   * Creates a {@link LocationFactory} that always applies the giving namespace prefix.
   */
  public static LocationFactory namespace(LocationFactory delegate, final String namespace) {
    return new ForwardingLocationFactory(delegate) {
      @Override
      public Location create(String path) {
        try {
          Location base = getDelegate().create(namespace);
          return base.append(path);
        } catch (IOException e) {
          throw Throwables.propagate(e);
        }
      }

      @Override
      public Location create(URI uri) {
        if (uri.isAbsolute()) {
          return getDelegate().create(uri);
        }
        try {
          Location base = getDelegate().create(namespace);
          return base.append(uri.getPath());
        } catch (IOException e) {
          throw Throwables.propagate(e);
        }
      }

      @Override
      public Location getHomeLocation() {
        return getDelegate().getHomeLocation();
      }
    };
  }

  private LocationFactories() {
  }
}
