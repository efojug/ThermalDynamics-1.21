package cofh.thermal.dynamics.handler;

import cofh.thermal.dynamics.util.GraphHelper;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author covers1624
 */
@SuppressWarnings ("UnstableApiUsage")
public class GraphHelperTest {

    @Test
    public void testSeparateGraphs() {

        Object a = new Object();
        Object b = new Object();
        Object edge = new Object();
        MutableGraph<Object> graph = GraphBuilder.undirected().build();
        graph.addNode(a);
        graph.addNode(b);
        graph.addNode(edge);

        graph.putEdge(a, edge);
        graph.putEdge(b, edge);

        List<Set<Object>> separated = GraphHelper.separateGraphs(graph);
        assertEquals(1, separated.size());
        assertEquals(Set.of(a, b, edge), separated.getFirst());

        graph.removeEdge(a, edge);
        separated = GraphHelper.separateGraphs(graph);
        assertEquals(2, separated.size());
        assertTrue(separated.contains(Set.of(a)));
        assertTrue(separated.contains(Set.of(b, edge)));
    }

    @Test
    public void testEmptyAndIsolatedGraphs() {

        MutableGraph<Object> graph = GraphBuilder.undirected().build();
        assertTrue(GraphHelper.separateGraphs(graph).isEmpty());

        Object a = new Object();
        Object b = new Object();
        graph.addNode(a);
        graph.addNode(b);
        List<Set<Object>> separated = GraphHelper.separateGraphs(graph);
        assertEquals(2, separated.size());
        assertTrue(separated.contains(Set.of(a)));
        assertTrue(separated.contains(Set.of(b)));
    }
}
