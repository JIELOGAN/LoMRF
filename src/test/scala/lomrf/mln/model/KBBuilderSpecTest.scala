/*
 * o                        o     o   o         o
 * |             o          |     |\ /|         | /
 * |    o-o o--o    o-o  oo |     | O |  oo o-o OO   o-o o   o
 * |    | | |  | | |    | | |     |   | | | |   | \  | |  \ /
 * O---oo-o o--O |  o-o o-o-o     o   o o-o-o   o  o o-o   o
 *             |
 *          o--o
 * o--o              o               o--o       o    o
 * |   |             |               |    o     |    |
 * O-Oo   oo o-o   o-O o-o o-O-o     O-o    o-o |  o-O o-o
 * |  \  | | |  | |  | | | | | |     |    | |-' | |  |  \
 * o   o o-o-o  o  o-o o-o o o o     o    | o-o o  o-o o-o
 *
 * Logical Markov Random Fields.
 *
 * Copyright (C) 2012  Anastasios Skarlatidis.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package lomrf.mln.model

import lomrf.logic.{KBParser, AtomSignature}
import org.scalatest.{Matchers, FunSpec}
import lomrf.logic.predef.{dynFunctions, dynAtoms}

/**
 * A series of API behaviour tests for KBBuilder
 */
class KBBuilderSpecTest extends FunSpec with Matchers {

  private val samplePredicateSchema = Map(
    AtomSignature("InitiatedAt", 2) -> Vector("fluent", "time"),
    AtomSignature("TerminatedAt", 2) -> Vector("fluent", "time"),
    AtomSignature("Happens", 2) -> Vector("event", "time"),
    AtomSignature("HoldsAt", 2) -> Vector("fluent", "time"))

  private val sampleFunctionsSchema = Map(
    AtomSignature("walking", 1) ->("event", Vector("id")),
    AtomSignature("move", 2) ->("fluent", Vector("id", "id")))

  private val parser = new KBParser(samplePredicateSchema, sampleFunctionsSchema)

  private val samplePredicateSchemas = List(
    ("HoldsAt", Vector("fluent", "time")),
    ("HappensAt", Vector("event", "time")),
    ("InitiatedAt", Vector("fluent", "time")),
    ("TerminatedAt", Vector("fluent", "time")),
    ("Initiates", Vector("event","fluent", "time")),
    ("Terminates", Vector("event","fluent", "time"))
  ).map(schema => AtomSignature(schema._1, schema._2.size) -> schema._2)


  val sampleFormulas = Seq(
    "0.32 Happens(Walking, t) => InitiatedAt(Moving,t)",
    "Happens(walking(person1), t) ^ Happens(walking(person2), t) => InitiatedAt(move(person1,person2),t).",
    "1.27 InitiatedAt(f, t) => HoldsAt(f, t + 1)",
    "InitiatedAt(f, t) => HoldsAt(f, t++)."
  ).map(parser.parseFormula)


  // --------------------------------------------------------
  // --- KB Builder (creation of empty KB)
  // --------------------------------------------------------
  describe("The KB from an empty KBBuilder") {
    val builder = KBBuilder()

    val kb = builder.result()

    it("should be composed of an empty Set of formulas") {
      assert(kb.formulas.isEmpty)
    }

    it("should be composed of an empty Map of predicate schema") {
      assert(kb.predicateSchema.isEmpty)
    }

    it("should be composed of an empty Map of function schema") {
      assert(kb.functionSchema.isEmpty)
    }

    it("should be composed of an empty Map of dynamic predicates") {
      assert(kb.dynamicPredicates.isEmpty)
    }

    it("should be composed of an empty Map of dynamic functions") {
      assert(kb.dynamicFunctions.isEmpty)
    }
  }

  // --------------------------------------------------------
  // --- KB Builder (insertion of predicate schema)
  // --------------------------------------------------------
  describe("KBBuilder insertion of predicate schemas"){

    describe("Incremental addition of predicate schemas") {
      val builder = KBBuilder()

      for (schema <- samplePredicateSchemas)
        builder.predicateSchema += schema


      it(s"contains ${samplePredicateSchemas.size} predicate schema definitions"){
        builder.predicateSchema().size shouldEqual samplePredicateSchemas.size
      }

      it(s"contains ${samplePredicateSchemas.size} predicate schema definitions, after re-adding the same schema definitions") {
        for (schema <- samplePredicateSchemas)
          builder.predicateSchema += schema

        builder.predicateSchema().size shouldEqual samplePredicateSchemas.size
      }

      it("contains all the incrementally inserted schema definitions") {
        val kb = builder.result()

        val predicateSchema = kb.predicateSchema

        assert(samplePredicateSchemas.forall(schema => predicateSchema(schema._1) == schema._2))
      }
    }

    describe("Batch addition of predicate schemas") {
      val builder = KBBuilder()

      builder.predicateSchema ++= samplePredicateSchemas


      it(s"contains ${samplePredicateSchemas.size} predicate schema definitions"){
        builder.predicateSchema().size shouldEqual samplePredicateSchemas.size
      }

      it(s"contains ${samplePredicateSchemas.size} predicate schema definitions, after re-adding the same schema definitions") {
        builder.predicateSchema ++= samplePredicateSchemas

        builder.predicateSchema().size shouldEqual samplePredicateSchemas.size
      }

      it("contains all the batched inserted schema definitions") {
        val kb = builder.result()

        val predicateSchema = kb.predicateSchema

        assert(samplePredicateSchemas.forall(schema => predicateSchema(schema._1) == schema._2))
      }
    }
  }

  // --------------------------------------------------------
  // --- KB Builder (insertion of function schema)
  // --------------------------------------------------------
  describe("KBBuilder insertion of function schemas"){


    describe("Incremental addition of function schemas") {
      val builder = KBBuilder()

      for (schema <- sampleFunctionsSchema)
        builder.functionSchema += schema


      it(s"contains ${sampleFunctionsSchema.size} function schema definitions"){
        builder.functionSchema().size shouldEqual sampleFunctionsSchema.size
      }

      it(s"contains ${sampleFunctionsSchema.size} function schema definitions, after re-adding the same schema definitions") {
        for (schema <- sampleFunctionsSchema)
          builder.functionSchema += schema

        builder.functionSchema().size shouldEqual sampleFunctionsSchema.size
      }

      it("contains all the incrementally inserted schema definitions") {
        val kb = builder.result()

        val functionSchema = kb.functionSchema

        assert(sampleFunctionsSchema.forall(schema => functionSchema(schema._1) == schema._2))
      }
    }

    describe("Batch addition of function schemas") {
      val builder = KBBuilder()

      builder.functionSchema ++= sampleFunctionsSchema


      it(s"contains ${sampleFunctionsSchema.size} function schema definitions"){
        builder.functionSchema().size shouldEqual sampleFunctionsSchema.size
      }

      it(s"contains ${sampleFunctionsSchema.size} function schema definitions, after re-adding the same schema definitions") {
        builder.functionSchema ++= sampleFunctionsSchema

        builder.functionSchema().size shouldEqual sampleFunctionsSchema.size
      }

      it("contains all the batched inserted schema definitions") {
        val kb = builder.result()

        val functionSchema = kb.functionSchema

        assert(sampleFunctionsSchema.forall(schema => functionSchema(schema._1) == schema._2))
      }
    }
  }

  // --------------------------------------------------------
  // --- KB Builder (insertion of dynamic predicate schemas)
  // --------------------------------------------------------
  describe("KBBuilder insertion of dynamic predicate schemas"){

    describe("Incremental addition of dynamic predicate schemas") {
      val builder = KBBuilder()

      for (schema <- dynAtoms)
        builder.dynamicPredicates += schema


      it(s"contains ${dynAtoms.size} dynamic predicate schema definitions"){
        builder.dynamicPredicates().size shouldEqual dynAtoms.size
      }

      it(s"contains ${dynAtoms.size} dynamic predicate schema definitions, after re-adding the same schema definitions") {
        for (schema <- dynAtoms)
          builder.dynamicPredicates += schema

        builder.dynamicPredicates().size shouldEqual dynAtoms.size
      }

      it("contains all the incrementally inserted schema definitions") {
        val kb = builder.result()

        val dynamicPredicates = kb.dynamicPredicates

        assert(dynAtoms.forall(schema => dynamicPredicates(schema._1) == schema._2))
      }
    }

    describe("Batch addition of dynamic predicate schemas") {
      val builder = KBBuilder()

      builder.dynamicPredicates ++= dynAtoms


      it(s"contains ${dynAtoms.size} dynamic predicate schema definitions"){
        builder.dynamicPredicates().size shouldEqual dynAtoms.size
      }

      it(s"contains ${dynAtoms.size} dynamic predicate schema definitions, after re-adding the same schema definitions") {
        builder.dynamicPredicates ++= dynAtoms

        builder.dynamicPredicates().size shouldEqual dynAtoms.size
      }

      it("contains all the batched inserted schema definitions") {
        val kb = builder.result()

        val dynamicPredicates = kb.dynamicPredicates

        assert(dynAtoms.forall(schema => dynamicPredicates(schema._1) == schema._2))
      }
    }
  }

  // --------------------------------------------------------
  // --- KB Builder (insertion of dynamic function schemas)
  // --------------------------------------------------------
  describe("KBBuilder insertion of dynamic function schemas"){

    describe("Incremental addition of dynamic function schemas") {
      val builder = KBBuilder()

      for (schema <- dynFunctions)
        builder.dynamicFunctions += schema


      it(s"contains ${dynFunctions.size} dynamic function schema definitions"){
        builder.dynamicFunctions().size shouldEqual dynFunctions.size
      }

      it(s"contains ${dynFunctions.size} dynamic function schema definitions, after re-adding the same schema definitions") {
        for (schema <- dynFunctions)
          builder.dynamicFunctions += schema

        builder.dynamicFunctions().size shouldEqual dynFunctions.size
      }

      it("contains all the incrementally inserted schema definitions") {
        val kb = builder.result()

        val dynamicFunctions = kb.dynamicFunctions

        assert(dynFunctions.forall(schema => dynamicFunctions(schema._1) == schema._2))
      }
    }

    describe("Batch addition of dynamic function schemas") {
      val builder = KBBuilder()

      builder.dynamicFunctions ++= dynFunctions


      it(s"contains ${dynFunctions.size} dynamic function schema definitions"){
        builder.dynamicFunctions().size shouldEqual dynFunctions.size
      }

      it(s"contains ${dynFunctions.size} dynamic function schema definitions, after re-adding the same schema definitions") {
        builder.dynamicFunctions ++= dynFunctions

        builder.dynamicFunctions().size shouldEqual dynFunctions.size
      }

      it("contains all the batched inserted schema definitions") {
        val kb = builder.result()

        val dynamicFunctions = kb.dynamicFunctions

        assert(dynFunctions.forall(schema => dynamicFunctions(schema._1) == schema._2))
      }
    }
  }


  // --------------------------------------------------------
  // --- KB Builder (insertion of formulas)
  // --------------------------------------------------------
  describe("KBBuilder insertion of formulas"){

    describe("Incremental addition of formulas") {
      val builder = KBBuilder()

      for(formula <- sampleFormulas)
        builder.formulas += formula


      it(s"contains ${sampleFormulas.size} dynamic function schema definitions"){
        builder.formulas().size shouldEqual sampleFormulas.size
      }

      it(s"contains ${sampleFormulas.size} dynamic function schema definitions, after re-adding the same schema definitions") {
        for(formula <- sampleFormulas)
          builder.formulas += formula

        builder.formulas().size shouldEqual sampleFormulas.size
      }

      it("contains all the incrementally inserted formulas") {
        val kb = builder.result()

        val formulas = kb.formulas

        assert(sampleFormulas.forall(formulas.contains))
      }

    }

    describe("Batch addition of formulas") {
      val builder = KBBuilder()

      builder.formulas ++= sampleFormulas


      it(s"contains ${sampleFormulas.size} dynamic function schema definitions"){
        builder.formulas().size shouldEqual sampleFormulas.size
      }

      it(s"contains ${sampleFormulas.size} dynamic function schema definitions, after re-adding the same schema definitions") {
        builder.formulas ++= sampleFormulas

        builder.formulas().size shouldEqual sampleFormulas.size
      }

      it("contains all the incrementally inserted formulas") {
        val kb = builder.result()

        val formulas = kb.formulas

        assert(sampleFormulas.forall(formulas.contains))
      }

    }
  }

  // --------------------------------------------------------
  // --- KB Builder: creation of KB
  // --------------------------------------------------------
  describe("The KB from a KBBuilder") {

    val builder = KBBuilder()

    val kb =
      builder
        .withPredicateSchema(samplePredicateSchema)
        .withFunctionSchema(sampleFunctionsSchema)
        .withFormulas(sampleFormulas.toSet)
        .withDynamicFunctions(dynFunctions)
        .withDynamicPredicates(dynAtoms)
        .result()

    it("contains all specified predicate schemas"){
      assert(samplePredicateSchema == kb.predicateSchema)
    }

    it("contains all specified function schemas"){
      assert(sampleFunctionsSchema == kb.functionSchema)
    }

    it("contains all specified formulas"){
      assert(sampleFormulas.forall(kb.formulas.contains))
    }

    it("contains all specified dynamic functions"){
      assert(dynFunctions == kb.dynamicFunctions)
    }

    it("contains all specified dynamic predicates"){
      assert(dynAtoms == kb.dynamicPredicates)
    }

  }


}