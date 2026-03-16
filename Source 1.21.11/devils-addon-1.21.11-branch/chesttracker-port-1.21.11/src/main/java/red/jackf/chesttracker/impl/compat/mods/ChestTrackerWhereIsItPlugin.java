package com.example.addon.chesttracker.impl.compat.mods;

import com.example.addon.chesttracker.api.providers.ProviderUtils;
import com.example.addon.chesttracker.impl.memory.MemoryBankAccessImpl;
import red.jackf.whereisit.client.api.WhereIsItClientPlugin;
import red.jackf.whereisit.client.api.events.SearchInvoker;

public class ChestTrackerWhereIsItPlugin implements WhereIsItClientPlugin {
    @Override
    public void load() {
        // add our memories as a handler for where is it
        SearchInvoker.EVENT.register((request, resultConsumer) -> {
            var currentKey = ProviderUtils.getPlayersCurrentKey();
            if (currentKey.isEmpty())
                return false;

            var bank = MemoryBankAccessImpl.INSTANCE.getLoadedInternal();
            if (bank.isEmpty())
                return false;

            var results = bank.get().doSearch(currentKey.get(), request);
            if (!results.isEmpty())
                resultConsumer.accept(results);
            return true;
        });
    }
}
