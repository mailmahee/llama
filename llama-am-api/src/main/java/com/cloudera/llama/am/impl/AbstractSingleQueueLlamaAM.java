/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.llama.am.impl;

import com.cloudera.llama.am.LlamaAM;
import com.cloudera.llama.am.LlamaAMException;
import com.cloudera.llama.am.LlamaAMListener;
import com.cloudera.llama.am.PlacedReservation;
import com.cloudera.llama.am.PlacedResource;
import com.cloudera.llama.am.Reservation;
import com.cloudera.llama.am.Resource;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public abstract class AbstractSingleQueueLlamaAM extends LlamaAM implements 
    Configurable {
  static final String QUEUE_KEY = PREFIX_KEY + "single.queue";

  private Logger logger;
  private Configuration conf;
  private LlamaAMListener listener;
  private ReentrantReadWriteLock.WriteLock lock;
  private Map<UUID, PlacedReservationImpl> reservationsMap;
  private Map<UUID, PlacedResourceImpl> resourcesMap;

  public AbstractSingleQueueLlamaAM() {
    logger = LoggerFactory.getLogger(getClass());
  }

  protected Logger getLog() {
    return logger;
  }

  // Configurable API

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  // LlamaAM API

  @Override
  public void start() throws LlamaAMException {
    String queue = getConf().get(QUEUE_KEY);
    if (queue == null) {
      throw new IllegalStateException("Missing '" + QUEUE_KEY +
          "' configuration property");
    }
    lock = new ReentrantReadWriteLock().writeLock();
    reservationsMap = new HashMap<UUID, PlacedReservationImpl>();
    resourcesMap = new HashMap<UUID, PlacedResourceImpl>();
    rmStart(queue);
  }

  @Override
  public synchronized void stop() {
    rmStop();
  }

  @Override
  public List<String> getNodes() throws LlamaAMException {
    return rmGetNodes();
  }

  @Override
  public void addListener(LlamaAMListener listener) {
    this.listener = listener;
  }

  @Override
  public void removeListener(LlamaAMListener listener) {
    if (this.listener == listener) {
      this.listener = null;
    }
  }

  void _addReservation(PlacedReservationImpl reservation) {
    UUID reservationId = reservation.getReservationId();
    reservationsMap.put(reservationId, reservation);
    for (PlacedResourceImpl resource : reservation.getResourceImpls()) {
      resource.setStatus(PlacedResource.Status.PENDING);
      UUID clientResourceId = resource.getClientResourceId();
      resourcesMap.put(clientResourceId, resource);
    }
  }

  PlacedReservationImpl _getReservation(UUID reservationId) {
    return reservationsMap.get(reservationId);
  }

  PlacedReservationImpl _deleteReservation(UUID reservationId) {
    PlacedReservationImpl reservation = reservationsMap.remove(reservationId);
    if (reservation != null) {
      for (Resource resource : reservation.getResources()) {
        resourcesMap.remove(resource.getClientResourceId());
      }
    }
    return reservation;
  }

  private <T> T sync(Callable<T> callable) {
    try {
      lock.lock();
      return callable.call();
    } catch (Throwable ex) {
      Thrower.throwEx(ex);
    } finally {
      lock.unlock();
    }
    return null;
  }

  @Override
  public UUID reserve(final Reservation reservation) throws LlamaAMException {
    final PlacedReservationImpl impl = new PlacedReservationImpl(reservation);
    rmReserve(impl);
    sync(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        _addReservation(impl);
        return null;
      }
    });
    return impl.getReservationId();
  }

  @Override
  public PlacedReservation getReservation(final UUID reservationId)
      throws LlamaAMException {
    return sync(new Callable<PlacedReservationImpl>() {
      @Override
      public PlacedReservationImpl call() throws Exception {
        return _getReservation(reservationId);
      }
    });
  }

  @Override
  public void releaseReservation(final UUID reservationId) throws LlamaAMException {
    PlacedReservationImpl reservation = sync(new 
                                                 Callable<PlacedReservationImpl>() {
      @Override
      public PlacedReservationImpl call() throws Exception {
        return _deleteReservation(reservationId);
      }
    });
    if (reservation != null) {
      rmRelease(reservation.getResources());
    } else {
      getLog().warn("Unknown reservationId '{}'", reservationId);
    }
  }

  @Override
  public void releaseReservationsForClientId(UUID clientId)
      throws LlamaAMException {
    List<PlacedReservation> reservations = sync(
        new Callable<List<PlacedReservation>>() {
      @Override
      public List<PlacedReservation> call() throws Exception {
        List<PlacedReservation> reservations = 
            new ArrayList<PlacedReservation>();
        for (PlacedReservation reservation : 
            new ArrayList<PlacedReservation>(reservationsMap.values())) {
          _deleteReservation(reservation.getReservationId());
          reservations.add(reservation);
        }
        return reservations;
      }
    });
    for (PlacedReservation reservation : reservations) {
      if (reservation != null) {
        rmRelease(reservation.getResources());
      }
    }
  }

  private LlamaAMEventImpl getEventForClientId(Map<UUID,
      LlamaAMEventImpl> eventsMap, UUID clientId) {
    LlamaAMEventImpl event = eventsMap.get(clientId);
    if (event == null) {
      event = new LlamaAMEventImpl(clientId);
      eventsMap.put(clientId, event);
    }
    return event;
  }

  private void _resourceRejected(PlacedResourceImpl resource,
      Map<UUID, LlamaAMEventImpl> eventsMap) throws LlamaAMException {
    resource.setStatus(PlacedResource.Status.REJECTED);
    UUID reservationId = resource.getReservationId();
    PlacedReservationImpl reservation = reservationsMap.get(reservationId);
    if (reservation == null) {
      getLog().warn("Reservation '{}' during resource '{}' rejection " + 
          "handling", reservationId, resource.getClientResourceId());
    }
    // if reservation is NULL it means it is gone already
    if (reservation != null) {
      LlamaAMEventImpl event = getEventForClientId(eventsMap,
          reservation.getClientId());
      // if reservation is ALLOCATED or it is PARTIAL and not GANG we let it be
      // and in the ELSE we notify the resource rejection
      switch (reservation.getStatus()) {
        case PENDING:
        case PARTIAL:
          if (reservation.isGang()) {
            _deleteReservation(reservationId);
            rmRelease(reservation.getResources());
            event.getRejectedReservationIds().add(reservationId);
          }
          event.getRejectedClientResourcesIds().add(resource
              .getClientResourceId());
          break;
        case ALLOCATED:
          event.getRejectedClientResourcesIds().add(resource
              .getClientResourceId());
          break;
      }
    }
  }

  private void _resourceAllocated(PlacedResourceImpl resource,
      RMResourceChange change, Map<UUID, LlamaAMEventImpl> eventsMap) {
    resource.setAllocationInfo(change.getvCpuCores(), change.getMemoryMb(), 
        change.getLocation(), change.getRmResourceId());
    UUID reservationId = resource.getReservationId();
    PlacedReservationImpl reservation = reservationsMap.get(reservationId);
    if (reservation == null) {
      getLog().warn("Reservation '{}' during resource allocation handling " +
          "for" + " '{}'", reservationId, resource.getClientResourceId());
    }
    if (reservation != null) {
      LlamaAMEventImpl event = getEventForClientId(eventsMap,
          reservation.getClientId());
      List<PlacedResourceImpl> resources = reservation.getResourceImpls();
      boolean fulfilled = true;
      for (int i = 0; fulfilled && i < resources.size(); i++) {
        fulfilled = resources.get(i).getStatus() == PlacedResource.Status
            .ALLOCATED;
      }
      if (fulfilled) {
        reservation.setStatus(PlacedReservation.Status.ALLOCATED);
        event.getAllocatedReservationIds().add(reservationId);
        if (reservation.isGang()) {
          event.getAllocatedResources().addAll(reservation.getResourceImpls());
        } else {
          event.getAllocatedResources().add(resource);          
        }
      } else {
        reservation.setStatus(PlacedReservation.Status.PARTIAL);
        if (!reservation.isGang()) {
          event.getAllocatedResources().add(resource);
        }
      }
    }
  }

  private void _resourcePreempted(PlacedResourceImpl resource,
      Map<UUID, LlamaAMEventImpl> eventsMap) throws LlamaAMException {
    resource.setStatus(PlacedResource.Status.PREEMPTED);
    UUID reservationId = resource.getReservationId();
    PlacedReservationImpl reservation = reservationsMap.get(reservationId);
    if (reservation == null) {
      getLog().warn("Reservation '{}' during resource preemption handling " +
          "for" + " '{}'", reservationId, resource.getClientResourceId());
    }
    if (reservation != null) {
      LlamaAMEventImpl event = getEventForClientId(eventsMap,
          reservation.getClientId());
      switch (reservation.getStatus()) {
        case ALLOCATED:
          event.getPreemptedClientResourceIds().add(
              resource.getClientResourceId());
          break;
        case PARTIAL:
          if (reservation.isGang()) {
            _deleteReservation(reservationId);
            rmRelease(reservation.getResources());
            event.getRejectedReservationIds().add(reservationId);
          } else {
            event.getPreemptedClientResourceIds().add(
                resource.getClientResourceId());
          }
          break;
        case PENDING:
          throw new IllegalStateException(FastFormat.format("Reservation "
              + "'{}' in pending, cannot have a preempted resource '{}'", 
              reservationId, resource.getClientResourceId()));
      }
    }
  }

  private void _resourceLost(PlacedResourceImpl resource,
      Map<UUID, LlamaAMEventImpl> eventsMap) throws LlamaAMException {
    resource.setStatus(PlacedResource.Status.LOST);
    UUID reservationId = resource.getReservationId();
    PlacedReservationImpl reservation = reservationsMap.get(reservationId);
    if (reservation == null) {
      getLog().warn("Reservation '{}' during resource lost handling  for " + 
          "'{}'", reservationId, resource.getClientResourceId());
    }
    if (reservation != null) {
      LlamaAMEventImpl event = getEventForClientId(eventsMap,
          reservation.getClientId());
      switch (reservation.getStatus()) {
        case ALLOCATED:
          event.getLostClientResourcesIds().add(resource.getClientResourceId());
          break;
        case PARTIAL:
          if (reservation.isGang()) {
            _deleteReservation(reservationId);
            rmRelease(reservation.getResources());
            event.getRejectedReservationIds().add(reservationId);
          } else {
            event.getLostClientResourcesIds().add(resource
                .getClientResourceId());
          }
          break;
        case PENDING:
          throw new IllegalStateException(FastFormat.format("Reservation "
              + "'{}' in pending, cannot have a lost resource '{}'", 
              reservationId, resource.getClientResourceId()));
      }
    }
  }

  // API to wire with RM client

  protected void rmChanges(final List<RMResourceChange> changes) {
    if (changes == null) {
      throw new IllegalArgumentException("changes cannot be NULL");
    }
    final boolean hasListener = listener != null;
    getLog().trace("rmChanges({})", changes);
    Map<UUID, LlamaAMEventImpl> eventsMap = 
        sync(new Callable<Map<UUID, LlamaAMEventImpl>>() {
      @Override
      public Map<UUID, LlamaAMEventImpl> call() throws Exception {
        Map<UUID, LlamaAMEventImpl> eventsMap = 
            new HashMap<UUID, LlamaAMEventImpl>();
        for (RMResourceChange change : changes) {
          PlacedResourceImpl resource = resourcesMap.get(change
              .getClientResourceId());
          if (resource == null) {
            getLog().warn("Unknown resource '{}'", 
                change.getClientResourceId());
          } else {
            switch (change.getStatus()) {
              case REJECTED:
                _resourceRejected(resource, eventsMap);
                break;
              case ALLOCATED:
                _resourceAllocated(resource, change, eventsMap);
                break;
              case PREEMPTED:
                _resourcePreempted(resource, eventsMap);
                break;
              case LOST:
                _resourceLost(resource, eventsMap);
                break;
            }
          }
        }
        return eventsMap;
      }
    });
    if (hasListener && !eventsMap.isEmpty()) {
        for (LlamaAMEventImpl event : eventsMap.values()) {
          try {
            if (!event.isEmpty()) {
              listener.handle(event);
            }
          } catch (Throwable ex) {
            getLog().warn("listener.handle() error: {}", ex.toString(), ex);
          }
        }
    }
  }

  protected abstract void rmStart(String queue) throws LlamaAMException;

  protected abstract void rmStop();

  protected abstract List<String> rmGetNodes() throws LlamaAMException;
  
  protected abstract void rmReserve(PlacedReservation reservation)
      throws LlamaAMException;

  protected abstract void rmRelease(Collection<PlacedResource> resources)
      throws LlamaAMException;

}