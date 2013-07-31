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
package com.continuuity.weave.discovery;

import com.continuuity.weave.common.Cancellable;
import com.continuuity.weave.common.Threads;
import com.continuuity.weave.zookeeper.NodeChildren;
import com.continuuity.weave.zookeeper.NodeData;
import com.continuuity.weave.zookeeper.OperationFuture;
import com.continuuity.weave.zookeeper.ZKClient;
import com.continuuity.weave.zookeeper.ZKClients;
import com.continuuity.weave.zookeeper.ZKOperations;
import com.google.common.base.Charsets;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Zookeeper implementation of {@link DiscoveryService} and {@link DiscoveryServiceClient}.
 * <p>
 *   Discoverable services are registered within Zookeeper under the namespace 'discoverable' by default.
 *   If you would like to change the namespace under which the services are registered then you can pass
 *   in the namespace during construction of {@link ZKDiscoveryService}.
 * </p>
 *
 * <p>
 *   Following is a simple example of how {@link ZKDiscoveryService} can be used for registering services
 *   and also for discovering the registered services.
 *   <blockquote>
 *    <pre>
 *      {@code
 *
 *      DiscoveryService service = new ZKDiscoveryService(zkClient);
 *      service.register(new Discoverable() {
 *        @Override
 *        public String getName() {
 *          return 'service-name';
 *        }
 *
 *        @Override
 *        public InetSocketAddress getSocketAddress() {
 *          return new InetSocketAddress(hostname, port);
 *        }
 *      });
 *      ...
 *      ...
 *      Iterable<Discoverable> services = service.discovery("service-name");
 *      ...
 *      }
 *    </pre>
 *   </blockquote>
 * </p>
 */
public class ZKDiscoveryService implements DiscoveryService, DiscoveryServiceClient {
  private static final Logger LOG = LoggerFactory.getLogger(ZKDiscoveryService.class);
  private static final String NAMESPACE = "/discoverable";

  // In memory map for recreating ephemeral nodes after session expires.
  // It map from discoverable to the corresponding Cancellable
  private final Multimap<Discoverable, DiscoveryCancellable> discoverables;
  private final Lock lock;

  private final LoadingCache<String, Iterable<Discoverable>> services;
  private final ZKClient zkClient;

  /**
   * Constructs ZKDiscoveryService using the provided zookeeper client for storing service registry.
   * @param zkClient The {@link ZKClient} for interacting with zookeeper.
   */
  public ZKDiscoveryService(ZKClient zkClient) {
    this(zkClient, NAMESPACE);
  }

  /**
   * Constructs ZKDiscoveryService using the provided zookeeper client for storing service registry under namepsace.
   * @param zkClient of zookeeper quorum
   * @param namespace under which the service registered would be stored in zookeeper.
   *                  If namespace is {@code null}, no namespace will be used.
   */
  public ZKDiscoveryService(ZKClient zkClient, String namespace) {
    this.discoverables = HashMultimap.create();
    this.lock = new ReentrantLock();
    this.zkClient = namespace == null ? zkClient : ZKClients.namespace(zkClient, namespace);
    this.services = CacheBuilder.newBuilder().build(createServiceLoader());
    this.zkClient.addConnectionWatcher(createConnectionWatcher());
  }

  /**
   * Registers a {@link Discoverable} in zookeeper.
   * <p>
   *   Registering a {@link Discoverable} will create a node <base>/<service-name>
   *   in zookeeper as a ephemeral node. If the node already exists (timeout associated with emphemeral, then a runtime
   *   exception is thrown to make sure that a service with an intent to register is not started without registering.
   *   When a runtime is thrown, expectation is that the process being started with fail and would be started again
   *   by the monitoring service.
   * </p>
   * @param discoverable Information of the service provider that could be discovered.
   * @return An instance of {@link Cancellable}
   */
  @Override
  public Cancellable register(final Discoverable discoverable) {
    final Discoverable wrapper = new DiscoverableWrapper(discoverable);
    final SettableFuture<String> future = SettableFuture.create();
    final DiscoveryCancellable cancellable = new DiscoveryCancellable(wrapper);

    // Create the zk ephemeral node.
    Futures.addCallback(doRegister(wrapper), new FutureCallback<String>() {
      @Override
      public void onSuccess(String result) {
        // Set the sequence node path to cancellable for future cancellation.
        cancellable.setPath(result);
        lock.lock();
        try {
          discoverables.put(wrapper, cancellable);
        } finally {
          lock.unlock();
        }
        LOG.debug("Service registered: {} {}", wrapper, result);
        future.set(result);
      }

      @Override
      public void onFailure(Throwable t) {
        future.setException(t);
      }
    }, Threads.SAME_THREAD_EXECUTOR);

    Futures.getUnchecked(future);
    return cancellable;
  }

  @Override
  public Iterable<Discoverable> discover(String service) {
    return services.getUnchecked(service);
  }

  private OperationFuture<String> doRegister(Discoverable discoverable) {
    byte[] discoverableBytes = encode(discoverable);

    // Path /<service-name>/service-sequential
    final String sb = "/" + discoverable.getName() + "/service-";
    return zkClient.create(sb, discoverableBytes, CreateMode.EPHEMERAL_SEQUENTIAL, true);
  }

  private Watcher createConnectionWatcher() {
    return new Watcher() {
      // Watcher is invoked from single event thread, hence safe to use normal mutable variable.
      private boolean expired;

      @Override
      public void process(WatchedEvent event) {
        if (event.getState() == Event.KeeperState.Expired) {
          LOG.warn("ZK Session expired: {}", zkClient.getConnectString());
          expired = true;
        } else if (event.getState() == Event.KeeperState.SyncConnected && expired) {
          LOG.info("Reconnected after expiration: {}", zkClient.getConnectString());
          expired = false;

          // Re-register all services
          lock.lock();
          try {
            for (final Map.Entry<Discoverable, DiscoveryCancellable> entry : discoverables.entries()) {
              LOG.info("Re-registering service: {}", entry.getKey());

              // Must be non-blocking in here.
              Futures.addCallback(doRegister(entry.getKey()), new FutureCallback<String>() {
                @Override
                public void onSuccess(String result) {
                  // Updates the cancellable to the newly created sequential node.
                  entry.getValue().setPath(result);
                  LOG.debug("Service re-registered: {} {}", entry.getKey(), result);
                }

                @Override
                public void onFailure(Throwable t) {
                  // When failed to create the node, there would be no retry and simply make the cancellable do nothing.
                  entry.getValue().setPath(null);
                  LOG.error("Failed to re-register service: {}", entry.getKey(), t);
                }
              }, Threads.SAME_THREAD_EXECUTOR);
            }
          } finally {
            lock.unlock();
          }
        }
      }
    };
  }

  /**
   * Creates a CacheLoader for creating live Iterable for watching instances changes for a given service.
   */
  private CacheLoader<String, Iterable<Discoverable>> createServiceLoader() {
    return new CacheLoader<String, Iterable<Discoverable>>() {
      @Override
      public Iterable<Discoverable> load(String service) throws Exception {
        // The atomic reference is to keep the resulting Iterable live. It always contains a
        // immutable snapshot of the latest detected set of Discoverable.
        final AtomicReference<Iterable<Discoverable>> iterable =
              new AtomicReference<Iterable<Discoverable>>(ImmutableList.<Discoverable>of());
        final String serviceBase = "/" + service;

        // Watch for children changes in /service
        ZKOperations.watchChildren(zkClient, serviceBase, new ZKOperations.ChildrenCallback() {
          @Override
          public void updated(NodeChildren nodeChildren) {
            // Fetch data of all children nodes in parallel.
            List<String> children = nodeChildren.getChildren();
            List<OperationFuture<NodeData>> dataFutures = Lists.newArrayListWithCapacity(children.size());
            for (String child : children) {
              dataFutures.add(zkClient.getData(serviceBase + "/" + child));
            }

            // Update the service map when all fetching are done.
            final ListenableFuture<List<NodeData>> fetchFuture = Futures.successfulAsList(dataFutures);
            fetchFuture.addListener(new Runnable() {
              @Override
              public void run() {
                ImmutableList.Builder<Discoverable> builder = ImmutableList.builder();
                for (NodeData nodeData : Futures.getUnchecked(fetchFuture)) {
                  // For successful fetch, decode the content.
                  if (nodeData != null) {
                    Discoverable discoverable = decode(nodeData.getData());
                    if (discoverable != null) {
                      builder.add(discoverable);
                    }
                  }
                }
                iterable.set(builder.build());
              }
            }, Threads.SAME_THREAD_EXECUTOR);
          }
        });

        return new Iterable<Discoverable>() {
          @Override
          public Iterator<Discoverable> iterator() {
            return iterable.get().iterator();
          }
        };
      }
    };
  }

  /**
   * Static helper function for decoding array of bytes into a {@link DiscoverableWrapper} object.
   * @param bytes representing serialized {@link DiscoverableWrapper}
   * @return null if bytes are null; else an instance of {@link DiscoverableWrapper}
   */
  private static Discoverable decode(byte[] bytes) {
    if (bytes == null) {
      return null;
    }
    String content = new String(bytes, Charsets.UTF_8);
    return new GsonBuilder().registerTypeAdapter(Discoverable.class, new DiscoverableCodec())
      .create()
      .fromJson(content, Discoverable.class);
  }

  /**
   * Static helper function for encoding an instance of {@link DiscoverableWrapper} into array of bytes.
   * @param discoverable An instance of {@link DiscoverableWrapper}
   * @return array of bytes representing an instance of <code>discoverable</code>
   */
  private static byte[] encode(Discoverable discoverable) {
    return new GsonBuilder().registerTypeAdapter(DiscoverableWrapper.class, new DiscoverableCodec())
      .create()
      .toJson(discoverable, DiscoverableWrapper.class)
      .getBytes(Charsets.UTF_8);
  }


  /**
   * Inner class for cancelling (un-register) discovery service.
   */
  private final class DiscoveryCancellable implements Cancellable {

    private final Discoverable discoverable;
    private final AtomicBoolean cancelled;
    private volatile String path;

    DiscoveryCancellable(Discoverable discoverable) {
      this.discoverable = discoverable;
      this.cancelled = new AtomicBoolean();
    }

    void setPath(String path) {
      this.path = path;
      if (cancelled.get() && path != null) {
        // Simply delete the path if it's already cancelled
        // It's for the case when session expire happened and re-registration completed after this has been cancelled.
        zkClient.delete(path);
      }
    }

    @Override
    public void cancel() {
      if (!cancelled.compareAndSet(false, true)) {
        return;
      }

      // Take a snapshot of the volatile path.
      // If it is null, excepted the setPath will be called in future (through zk callback) so that
      // deletion will be done in setPath.
      String path = this.path;
      if (path == null) {
        return;
      }

      lock.lock();
      try {
        discoverables.remove(discoverable, this);
      } finally {
        lock.unlock();
      }

      // Delete the path. It's ok if the path not exists
      // (e.g. what session expired and before node has been re-created)
      Futures.getUnchecked(ZKOperations.ignoreError(zkClient.delete(path),
                                                    KeeperException.NoNodeException.class, path));
      LOG.debug("Service unregistered: {} {}", discoverable, path);
    }
  }

  /**
   * SerDe for converting a {@link DiscoverableWrapper} into a JSON object
   * or from a JSON object into {@link DiscoverableWrapper}.
   */
  private static final class DiscoverableCodec implements JsonSerializer<Discoverable>, JsonDeserializer<Discoverable> {

    @Override
    public Discoverable deserialize(JsonElement json, Type typeOfT,
                                    JsonDeserializationContext context) throws JsonParseException {
      JsonObject jsonObj = json.getAsJsonObject();
      final String service = jsonObj.get("service").getAsString();
      String hostname = jsonObj.get("hostname").getAsString();
      int port = jsonObj.get("port").getAsInt();
      final InetSocketAddress address = new InetSocketAddress(hostname, port);
      return new Discoverable() {
        @Override
        public String getName() {
          return service;
        }

        @Override
        public InetSocketAddress getSocketAddress() {
          return address;
        }
      };
    }

    @Override
    public JsonElement serialize(Discoverable src, Type typeOfSrc, JsonSerializationContext context) {
      JsonObject jsonObj = new JsonObject();
      jsonObj.addProperty("service", src.getName());
      jsonObj.addProperty("hostname", src.getSocketAddress().getHostName());
      jsonObj.addProperty("port", src.getSocketAddress().getPort());
      return jsonObj;
    }
  }
}

