package org.dizitart.no2.rocksdb;

import lombok.AccessLevel;
import lombok.Setter;
import org.dizitart.no2.common.module.NitritePlugin;
import org.dizitart.no2.store.NitriteStore;
import org.dizitart.no2.store.StoreModule;

import java.util.Set;

import static org.dizitart.no2.common.util.Iterables.setOf;

public class RocksDBModule implements StoreModule {
    private NitriteStore<?> nitriteStore;

    @Setter(AccessLevel.PACKAGE)
    private RocksDBConfig storeConfig;

    public RocksDBModule(String path) {
        this.storeConfig = new RocksDBConfig();
        this.storeConfig.filePath(path);
    }

    @Override
    public Set<NitritePlugin> plugins() {
        return setOf(getStore());
    }

    public static RocksDBModuleBuilder withConfig() {
        return new RocksDBModuleBuilder();
    }

    public NitriteStore<?> getStore() {
        if (nitriteStore == null) {
            RocksDBStore store = new RocksDBStore();
            store.setStoreConfig(storeConfig);
            nitriteStore = store;
        }
        return nitriteStore;
    }
}
