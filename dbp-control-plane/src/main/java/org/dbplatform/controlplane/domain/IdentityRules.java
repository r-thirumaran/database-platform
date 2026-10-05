package org.dbplatform.controlplane.domain;

import java.util.ArrayList;
import java.util.List;

/** How proxy and collectors map observed connections to an application (docs/control-plane-api.md §2). */
public class IdentityRules {
    private List<String> cidrs = new ArrayList<>();
    private List<String> programNames = new ArrayList<>();
    private List<String> machinePatterns = new ArrayList<>();
    private List<String> serviceAliases = new ArrayList<>();
    private List<String> pgApplicationNames = new ArrayList<>();

    public List<String> getCidrs() { return cidrs; }
    public void setCidrs(List<String> cidrs) { this.cidrs = cidrs == null ? new ArrayList<>() : cidrs; }
    public List<String> getProgramNames() { return programNames; }
    public void setProgramNames(List<String> programNames) { this.programNames = programNames == null ? new ArrayList<>() : programNames; }
    public List<String> getMachinePatterns() { return machinePatterns; }
    public void setMachinePatterns(List<String> machinePatterns) { this.machinePatterns = machinePatterns == null ? new ArrayList<>() : machinePatterns; }
    public List<String> getServiceAliases() { return serviceAliases; }
    public void setServiceAliases(List<String> serviceAliases) { this.serviceAliases = serviceAliases == null ? new ArrayList<>() : serviceAliases; }
    public List<String> getPgApplicationNames() { return pgApplicationNames; }
    public void setPgApplicationNames(List<String> pgApplicationNames) { this.pgApplicationNames = pgApplicationNames == null ? new ArrayList<>() : pgApplicationNames; }
}
