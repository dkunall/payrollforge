package com.payrollforge.payroll.domain.structure;

import com.payrollforge.payroll.domain.formula.Formula;
import com.payrollforge.payroll.domain.formula.FormulaException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Components and the two aggregates (GROSS, TAXABLE_GROSS) as a dependency graph, sorted so every formula
 * runs after what it reads. Kahn's algorithm, ties broken by the order HR declared the components in, so the
 * order is deterministic. A cycle is reported with its path.
 */
public final class ComponentGraph {

    /** A node is a component or an aggregate; {@code deps} are the nodes it reads. */
    record Node(String name, int declared, ComponentDef def, Formula formula, Set<String> deps) {
        boolean aggregate() {
            return def == null;
        }
    }

    private final List<Node> order;
    private final Map<String, Set<String>> dependsOn;

    private ComponentGraph(List<Node> order, Map<String, Set<String>> dependsOn) {
        this.order = order;
        this.dependsOn = dependsOn;
    }

    public static ComponentGraph build(List<ComponentDef> components) {
        if (components.isEmpty()) {
            throw new StructureException("structure-empty", "a structure needs at least one component");
        }
        var nodes = new LinkedHashMap<String, Node>();
        Set<String> reserved = new LinkedHashSet<>(Inputs.PROVIDED);
        reserved.addAll(Inputs.AGGREGATES);
        int i = 0;
        for (ComponentDef c : components) {
            if (reserved.contains(c.code())) {
                throw new StructureException("component-code", c.code() + " is a reserved input name");
            }
            if (nodes.containsKey(c.code())) {
                throw new StructureException("component-duplicate", "component " + c.code() + " is defined twice");
            }
            Formula f;
            try {
                f = Formula.parse(c.formula());
            } catch (FormulaException e) {
                throw new StructureException(e.code(), c.code() + ": " + e.getMessage());
            }
            nodes.put(c.code(), new Node(c.code(), i++, c, f, new LinkedHashSet<>()));
        }
        for (Node n : List.copyOf(nodes.values())) {
            for (String v : n.formula().variables()) {
                if (nodes.containsKey(v) || Inputs.AGGREGATES.contains(v)) {
                    n.deps().add(v);
                } else if (!Inputs.PROVIDED.contains(v)) {
                    throw new StructureException("formula-unknown-variable",
                            n.name() + " reads " + v + ", which is neither a component nor an input");
                }
            }
        }
        var gross = new LinkedHashSet<String>();
        var taxable = new LinkedHashSet<String>();
        for (Node n : nodes.values()) {
            if (n.def().kind() == ComponentKind.EARNING) {
                gross.add(n.name());
                if (n.def().taxable()) {
                    taxable.add(n.name());
                }
            }
        }
        nodes.put(Inputs.GROSS, new Node(Inputs.GROSS, i++, null, null, gross));
        nodes.put(Inputs.TAXABLE_GROSS, new Node(Inputs.TAXABLE_GROSS, i, null, null, taxable));

        var order = sort(nodes);
        var deps = new LinkedHashMap<String, Set<String>>();
        for (Node n : nodes.values()) {
            if (!n.aggregate()) {
                deps.put(n.name(), Set.copyOf(n.deps()));
            }
        }
        return new ComponentGraph(order, deps);
    }

    private static List<Node> sort(Map<String, Node> nodes) {
        var indegree = new HashMap<String, Integer>();
        var readers = new HashMap<String, List<String>>();
        for (Node n : nodes.values()) {
            indegree.put(n.name(), n.deps().size());
            for (String d : n.deps()) {
                readers.computeIfAbsent(d, k -> new ArrayList<>()).add(n.name());
            }
        }
        var ready = new PriorityQueue<Node>((a, b) -> Integer.compare(a.declared(), b.declared()));
        nodes.values().stream().filter(n -> n.deps().isEmpty()).forEach(ready::add);
        var out = new ArrayList<Node>(nodes.size());
        while (!ready.isEmpty()) {
            Node n = ready.poll();
            out.add(n);
            for (String r : readers.getOrDefault(n.name(), List.of())) {
                if (indegree.merge(r, -1, Integer::sum) == 0) {
                    ready.add(nodes.get(r));
                }
            }
        }
        if (out.size() != nodes.size()) {
            throw new StructureException("formula-cycle", "components depend on each other: " + cyclePath(nodes, out));
        }
        return List.copyOf(out);
    }

    /** Walks unsorted nodes until one repeats; the repeat closes the cycle. */
    private static String cyclePath(Map<String, Node> nodes, List<Node> sorted) {
        Set<String> done = new java.util.HashSet<>();
        sorted.forEach(n -> done.add(n.name()));
        String start = nodes.values().stream().filter(n -> !done.contains(n.name()))
                .min((a, b) -> Integer.compare(a.declared(), b.declared())).orElseThrow().name();
        var path = new ArrayList<String>();
        var seen = new HashMap<String, Integer>();
        String cur = start;
        while (!seen.containsKey(cur)) {
            seen.put(cur, path.size());
            path.add(cur);
            Node n = nodes.get(cur);
            cur = n.deps().stream().filter(d -> !done.contains(d)).findFirst().orElseThrow();
        }
        var cycle = new ArrayList<>(path.subList(seen.get(cur), path.size()));
        cycle.add(cur);
        return String.join(" -> ", cycle);
    }

    List<Node> order() {
        return order;
    }

    /** Evaluation order of the components only, for display. */
    public List<String> evaluationOrder() {
        return order.stream().filter(n -> !n.aggregate()).map(Node::name).toList();
    }

    /** Component code to the components and aggregates it reads. */
    public Map<String, Set<String>> dependsOn() {
        return dependsOn;
    }
}
