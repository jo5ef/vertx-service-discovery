/*
 * Copyright (c) 2011-2016 The original author or authors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.servicediscovery.consul;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.consul.ConsulClient;
import io.vertx.ext.consul.ConsulClientOptions;
import io.vertx.ext.consul.Service;
import io.vertx.ext.consul.ServiceList;
import io.vertx.servicediscovery.Record;
import io.vertx.servicediscovery.impl.ServiceTypes;
import io.vertx.servicediscovery.spi.ServiceImporter;
import io.vertx.servicediscovery.spi.ServicePublisher;
import io.vertx.servicediscovery.spi.ServiceType;
import io.vertx.servicediscovery.types.HttpLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A discovery bridge importing services from Consul.
 *
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class ConsulServiceImporter implements ServiceImporter {

  private ServicePublisher publisher;
  private ConsulClient client;

  private final static Logger LOGGER = LoggerFactory.getLogger(ConsulServiceImporter.class);

  private final List<ImportedConsulService> imports = new ArrayList<>();
  private long scanTask = -1;
  private Vertx vertx;

  @Override
  public void start(Vertx vertx, ServicePublisher publisher, JsonObject configuration, Future<Void> completion) {
    this.vertx = vertx;
    this.publisher = publisher;

    ConsulClientOptions opts = new ConsulClientOptions()
      .setHost(configuration.getString("host", "localhost"))
      .setPort(configuration.getInteger("port", 8500))
      .setDc(configuration.getString("dc"))
      .setAclToken(configuration.getString("acl_token"));

    client = ConsulClient.create(vertx, opts);

    Future<List<ImportedConsulService>> imports = Future.future();

    retrieveServicesFromConsul(imports);

    imports.setHandler(ar -> {
      if (ar.succeeded()) {
        Integer period = configuration.getInteger("scan-period", 2000);
        if (period != 0) {
          scanTask = vertx.setPeriodic(period, l -> {
            Future<List<ImportedConsulService>> future = Future.future();
            future.setHandler(ar2 -> {
              if (ar2.failed()) {
                LOGGER.warn("Consul importation has failed", ar.cause());
              }
            });
            retrieveServicesFromConsul(future);
          });
        }
        completion.complete();
      } else {
        completion.fail(ar.cause());
      }
    });

  }


  private Handler<Throwable> getErrorHandler(Future future) {
    return t -> {
      if (future != null) {
        if (!future.isComplete()) {
          future.fail(t);
        }
      } else {
        LOGGER.error(t);
      }
    };
  }

  private void retrieveServicesFromConsul(Future<List<ImportedConsulService>> completed) {
    client.catalogServices(ar -> {
      if (ar.succeeded()) {
        retrieveIndividualServices(ar.result(), completed);
      } else {
        completed.fail(ar.cause());
      }
    });
  }

  private void retrieveIndividualServices(ServiceList list, Future<List<ImportedConsulService>> completed) {
    List<Future> futures = new ArrayList<>();
    list.getList().forEach(service -> {

      Future<List<ImportedConsulService>> future = Future.future();
      client.catalogServiceNodes(service.getName(), ar -> {
        if (ar.succeeded()) {
          importService(ar.result().getList(), future);
        } else {
          completed.fail(ar.cause());
        }
      });

      futures.add(future);
    });

    CompositeFuture.all(futures).setHandler(ar -> {
      if (ar.failed()) {
        LOGGER.error("Fail to retrieve the services from consul", ar.cause());
      } else {
        List<ImportedConsulService> services =
            futures.stream().map(future -> ((Future<List<ImportedConsulService>>) future).result())
                .flatMap(Collection::stream)
                .collect(Collectors.toList());

        List<String> retrievedIds = services.stream().map(ImportedConsulService::id).collect(Collectors.toList());

        synchronized (ConsulServiceImporter.this) {

          List<String> existingIds = imports.stream().map(ImportedConsulService::id).collect(Collectors.toList());

          LOGGER.trace("Imported services: " + existingIds + ", Retrieved services form Consul: " + retrievedIds);

          services.forEach(svc -> {
            String id = svc.id();

            if (!existingIds.contains(id)) {
              LOGGER.info("Imported service: " + id);
              imports.add(svc);
            }
          });

          List<ImportedConsulService> toRemove = new ArrayList<>();
          imports.forEach(svc -> {
            if (!retrievedIds.contains(svc.id())) {
              LOGGER.info("Unregistering " + svc.id());
              toRemove.add(svc);
              svc.unregister(publisher, null);
            }
          });

          imports.removeAll(toRemove);
        }
      }

      if (ar.succeeded()) {
        completed.complete();
      } else {
        completed.fail(ar.cause());
      }
    });
  }

  private void importService(List<Service> list, Future<List<ImportedConsulService>> future) {
    if (list.isEmpty()) {
      future.fail("no service with the given name");
    } else {
      List<ImportedConsulService> importedServices = new ArrayList<>();
      List<Future> registrations = new ArrayList<>();
      for (int i = 0; i < list.size(); i++) {
        Future<Void> registration = Future.future();

        Service consulService = list.get(i);
        String id = consulService.getId();
        String name = consulService.getName();
        Record record = createRecord(consulService);

        // the id must be unique, so check if the service has already being imported
        ImportedConsulService imported = getImportedServiceById(id);
        if (imported != null) {
          importedServices.add(imported);
          registration.complete();
        } else {
          LOGGER.info("Importing service " + record.getName() + " (" + id + ")"
              + " from consul");
          ImportedConsulService service = new ImportedConsulService(name, id, record);
          service.register(publisher, Future.<ImportedConsulService>future().setHandler(res -> {
            if (res.succeeded()) {
              importedServices.add(res.result());
              registration.complete();
            } else {
              registration.fail(res.cause());
            }
          }));
        }
        registrations.add(registration);
      }

      CompositeFuture.all(registrations).setHandler(ar -> {
        if (ar.succeeded()) {
          future.complete(importedServices);
        } else {
          future.fail(ar.cause());
        }
      });
    }
  }

  private Record createRecord(Service service) {

    String address = service.getNodeAddress();
    String path = service.getAddress();
    int port = service.getPort();

    JsonObject metadata = service.toJson();
    if (service.getTags() != null) {
      service.getTags().forEach(tag -> metadata.put(tag, true));
    }

    Record record = new Record()
        .setName(service.getName())
        .setMetadata(metadata);

    // To determine the record type, check if we have a tag with a "type" name
    record.setType(ServiceType.UNKNOWN);
    ServiceTypes.all().forEachRemaining(type -> {
      if (metadata.getBoolean(type.name(), false)) {
        record.setType(type.name());
      }
    });

    JsonObject location = new JsonObject();
    location.put("host", address);
    location.put("port", port);
    if (path != null) {
      location.put("path", path);
    }

    // Manage HTTP endpoint
    if (record.getType().equals("http-endpoint")) {
      if (path != null) {
        location.put("root", path);
      }
      if (metadata.getBoolean("ssl", false)) {
        location.put("ssl", true);
      }
      location = new HttpLocation(location).toJson();
    }

    record.setLocation(location);

    if (service.getTags() != null) {
      service.getTags().stream()
        .filter(t -> t.startsWith("location:"))
        .map(t -> t.substring("location:".length()))
        .map(JsonObject::new)
        .map(HttpLocation::new)
        .findFirst()
        .ifPresent(loc -> record.setLocation(loc.toJson()));
    }

    return record;
  }

  private synchronized ImportedConsulService getImportedServiceById(String id) {
    for (ImportedConsulService svc : imports) {
      if (svc.id().equals(id)) {
        return svc;
      }
    }
    return null;
  }

  @Override
  public synchronized void close(Handler<Void> completionHandler) {
    if (scanTask != -1) {
      vertx.cancelTimer(scanTask);
    }
    // Remove all the services that has been imported
    List<Future> list = new ArrayList<>();
    imports.forEach(imported -> {
      Future<Void> fut = Future.future();
      fut.setHandler(ar -> {
        LOGGER.info("Unregistering " + imported.name());
        if (ar.succeeded()) {
          list.add(Future.succeededFuture());
        } else {
          list.add(Future.failedFuture(ar.cause()));
        }
      });
      imported.unregister(publisher, fut);
    });

    CompositeFuture.all(list).setHandler(ar -> {
      clearImportedServices();
      if (ar.succeeded()) {
        LOGGER.info("Successfully closed the service importer " + this);
      } else {
        LOGGER.error("A failure has been caught while stopping " + this, ar.cause());
      }
      if (completionHandler != null) {
        completionHandler.handle(null);
      }
    });
  }

  private synchronized void clearImportedServices(){
    imports.clear();
  }
}
