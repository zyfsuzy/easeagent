package com.megaease.easeagent.plugin.springweb;

import com.megaease.easeagent.plugin.AgentPlugin;
import com.megaease.easeagent.plugin.annotation.Plugin;
import com.megaease.easeagent.plugin.api.config.Config;

@Plugin
public class FeignClientPlugin implements AgentPlugin {

    @Override
    public String getName() {
        return "feignclient";
    }

    @Override
    public String getDomain() {
        return "observability";
    }

}