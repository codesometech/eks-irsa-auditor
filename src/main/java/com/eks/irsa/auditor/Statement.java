package com.eks.irsa.auditor;

import java.util.List;
import java.util.Map;

public class Statement {
   private List<String> actions;
   private List<String> resources;
   private String effect;
   private Map<String, Object> condition;

    public Map<String,Object> getCondition() {
        return this.condition;
    }

    public void setCondition(Map<String,Object> condition) {
        this.condition = condition;
    }

    public List<String> getActions() {
        return this.actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }

    public List<String> getResources() {
        return this.resources;
    }

    public void setResources(List<String> resources) {
        this.resources = resources;
    }

    public String getEffect() {
        return this.effect;
    }

    public void setEffect(String effect) {
        this.effect = effect;
    }


    @Override
    public String toString() {
        return "{" +
            " effect='" + getEffect() + "'" +
            ", actions='" + String.join(",",getActions()) + "'" +
            ", resources='" + String.join(",",getResources()) + "'" +
            "}";
    }


}
