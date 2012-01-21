package logger.graph

import collection.mutable.HashMap

trait DOTExportable {
  def exportInDOT(): Unit

  def escape(s: String) = s.replace("$", "")
}

sealed abstract class Vertex extends DOTExportable {
  val label: String

  def exportInDOT() = {
    println(label + ";")
  }
}
sealed abstract class Edge extends DOTExportable {
  val startVertex: Vertex
  val endVertex: Vertex

  def exportInDOT() = {
    println(startVertex.label + " -> " + endVertex.label)
  }
}

case class ActorVertex(val label: String) extends Vertex {
  override def exportInDOT() = {
    println(label + " [shape=diamond, color=green];")
  }
}
case class ObjectVertex(val label: String) extends Vertex {
  override def exportInDOT() = {
    // if label contains $ its a singleton
    if (label.contains("$")) {
      println(escape(label) + " [shape=box, color=brown];")
    } else super.exportInDOT()
  }
}

case class LabeledEdge(val startVertex: Vertex, val endVertex: Vertex, val label: String) extends Edge {
  override def exportInDOT() = {
    println(escape(startVertex.label) + " -> " + escape(endVertex.label) +
      " [ label= \"" + label + "\"];")

  }
}

case class ForwardEdge(override val startVertex: Vertex, override val endVertex: Vertex, override val label: String)
  extends LabeledEdge(startVertex, endVertex, label) {
  override def exportInDOT() = {
    println(escape(startVertex.label) + " -> " + escape(endVertex.label) +
      " [ label= \"" + label + "\" style=dotted];")

  }
}

object Implicits {

  final class EdgeAssoc[N1 <: Vertex](n1: N1) {
    def ~>[N2 <: Vertex](n2: N2)(l: String) = LabeledEdge(n1, n2, l)
    def -->[N2 <: Vertex](n2: N2)(l: String) = ForwardEdge(n1, n2, l)
  }

  implicit def any2EdgeAssoc[N1 <: Vertex](n: N1) = new EdgeAssoc(n)
}

class Graph {

  type V = Vertex
  type E = Edge

  private val adjacency = new HashMap[V, List[E]]
  private var edges: List[E] = Nil
  private var vertices: List[V] = Nil

  def add(vertex: V): Unit = {
    if (contains(vertex)) return

    adjacency(vertex) = Nil
    vertices = vertex :: vertices
  }

  def add(edge: E): Unit = {
    if (contains(edge)) return

    adjacency(edge.startVertex) = edge :: adjacency(edge.startVertex)
    edges = edge :: edges
  }

  def contains(vertex: V): Boolean = vertices contains vertex
  def contains(edge: E): Boolean = edges contains edge

  def incomingEdgesOf(vertex: V) = edges filter (_.endVertex == vertex)
  def outgoingEdgesOf(vertex: V) = adjacency(vertex)

  def +(edge: E) = { add(edge); this }
  def +(vertex: V) = { add(vertex); this }

  // print in dot notation
  def exportInDOT() = {

    println("digraph G {")

    vertices foreach (v ⇒ v.exportInDOT())
    edges foreach (e ⇒ e.exportInDOT())

    println("}")
  }
}