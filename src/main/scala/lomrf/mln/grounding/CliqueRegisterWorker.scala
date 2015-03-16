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

package lomrf.mln.grounding

import java.{util => jutil}

import akka.actor.{Actor, ActorRef}
import auxlib.log.Logging
import gnu.trove.list.array.TIntArrayList
import gnu.trove.map.TIntFloatMap
import gnu.trove.map.hash.{TIntFloatHashMap, TIntObjectHashMap}
import lomrf._
import lomrf.util.collection.PartitionedData

import scala.language.postfixOps
import scalaxy.streams.optimize


/**
 * CliqueRegisterWorker stores a partition of ground clauses that result from grounding workers.
 *
 * @param index the worker index (since we have multiple CliqueRegisterWorker instances),
 *              it also represents the partition index.
 * @param master reference to master actor, it is required in order to send the results back to master actor.
 * @param atomRegisters partitioned collection of AtomRegisterWorker actors, in order to send messages about ground
 *                      atom ids and their relation to ground clauses.
 * @param createDependencyMap when it is true the worker stores additional information about the relations between
 *                            FOL clauses and their groundings.
 */
private final class CliqueRegisterWorker private(
                                                  val index: Int,
                                                  master: ActorRef,
                                                  atomRegisters: PartitionedData[ActorRef],
                                                  createDependencyMap: Boolean) extends Actor with Logging {

  import messages._
  import context._

  private var hashCode2CliqueIDs = new TIntObjectHashMap[TIntArrayList]()
  private var cliques = new TIntObjectHashMap[CliqueEntry](DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR, NO_ENTRY_KEY)


  /**
   * This structure is useful when Machine Learning is used, it represents the relations between FOL clauses and their
   * groundings. Specifically, the structure stores for each ground clause the it of the FOL clause that becomes, as
   * well as how many times the this ground class is generated by the same FOL clause (freq). In a nutshell, the
   * structure of the 'cliqueDependencyMap' is the following:
   * {{{
   *  Map [ groundClause[ID:Int] -> Map [Clause[ID:Int] -> Freq:Int]]
   * }}}
   *
   * Please note that when the 'Freq' number is negative, then we implicitly declare that the  weight of the
   * corresponding FOL 'Clause[ID:Int]' has been inverted during the grounding process.
   */
  private var dependencyMap: DependencyMap = _

  private var cliqueID = 0


  /**
   * At the beginning, initialize the local dependency map (if it is set to store clause dependencies)
   */
  override def preStart(): Unit = {
    if (createDependencyMap)
      dependencyMap = new TIntObjectHashMap[TIntFloatMap](DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR, NO_ENTRY_KEY)
  }

  def receive = storeCliques

  /**
   * Accepts messages about ground clauses, in order to store them.
   *
   * @return a partial function with the actor logic for collecting ground clauses.
   */
  def storeCliques = ({
    /**
     * There are two procedures that can be performed for storing a ground clause:
     * <ul>
     * <li>(1) When the ground clause has a non-zero weight value, it is going to be stored.</li>
     * <li>(2) When the ground clause is unit and has weight equals to zero, then it is assumed as a ground query atom.
     * In that case the ground query atom is simple forwarded to the corresponding atom register.</li>
     * </ul>
     * Otherwise, the weight is zero and the clause will be ignored.
     *
     */
    case ce: CliqueEntry =>

      debug(s"CliqueRegister[$index] received '$ce' message.")

      // (1) Clause with some weight value, store the clause.
      // (2) Unit clause with zero weight is a query ground atom, thus forward to the corresponding atom register.
      if (ce.weight != 0) storeClique(ce)
      else if (ce.weight == 0 && ce.variables.length == 1)
        atomRegisters(ce.variables(0)) ! QueryVariable(ce.variables(0))

    /**
     * When the grounding of the MRF is complete, change the actor behaviour to 'results' and sent the total number of
     * collected ground clauses to master.
     */
    case GRND_Completed =>
      debug(s"CliqueRegister[$index] collected total ${cliques.size} cliques.")
      become(results) //change the actor logic
      sender ! NumberOfCliques(index, cliques.size())

  }: Receive) orElse handleUnknownMessage

  /**
   *
   * @return a partial function with the actor logic for clause re-indexing.
   */
  def results = ({
    case StartID(offset: Int) =>
      debug("CliqueRegister[" + index + "] received 'StartID(" + offset + ")' message.")

      val collectedCliques =
        if (offset == 0) {
          //do not adjust clique ids
          // Register (atomID -> cliqueID) mappings
          val iterator = cliques.iterator()
          while (iterator.hasNext) {
            iterator.advance()
            registerAtoms(iterator.value().variables, iterator.key())
          }
          CollectedCliques(index, cliques, if (createDependencyMap) Some(dependencyMap) else None)
        }
        else {
          //adjust clique ids
          hashCode2CliqueIDs = null //not needed anymore (allow GC to delete it)

          val resultingCliques = new TIntObjectHashMap[CliqueEntry](cliques.size() + 1, DEFAULT_LOAD_FACTOR, NO_ENTRY_KEY)

          val resultingDependencyMap: Option[DependencyMap] =
            if (createDependencyMap)
              Some(new TIntObjectHashMap[TIntFloatMap](DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR, NO_ENTRY_KEY))
            else None

          val iterator = cliques.iterator()
          var currentClique: CliqueEntry = null
          var finalID = NO_ENTRY_KEY
          var oldID = NO_ENTRY_KEY

          while (iterator.hasNext) {
            iterator.advance()

            oldID = iterator.key()
            finalID = oldID + offset
            currentClique = iterator.value()

            // Store clique mappings with the new 'final' id as key
            resultingCliques.put(finalID, currentClique)

            //if(createDependencyMap)
            resultingDependencyMap.foreach(_.put(finalID, dependencyMap.get(oldID)))

            // Register (atomID -> cliqueID) mappings
            registerAtoms(currentClique.variables, finalID)

          }

          // Not needed anymore (allow GC to delete it)
          cliques = null
          dependencyMap = null

          CollectedCliques(index, resultingCliques, resultingDependencyMap)
        }



      debug(s"CliqueRegister[$index] sending to master the CollectedCliques message, " +
            s"containing ${collectedCliques.cliques.size} cliques.")

      // send the collected and re-indexed partition of ground clauses to master.
      master ! collectedCliques

  }: Receive) orElse handleUnknownMessage

  /**
   * Reports as an error when an unknown message is being received.
   *
   * @return a partial function with the actor logic when an unknown message is being received.
   */
  private def handleUnknownMessage: Receive = {
    case msg =>
      error(s"CliqueRegister[$index] received an unknown message '$msg' from ${sender()}")
  }


  @inline private def registerAtoms(variables: Array[Int], cliqueID: Int): Unit = optimize {
    // Register (atomID -> cliqueID) mappings
    for (variable <- variables; atomID = math.abs(variable))
      atomRegisters(atomID) ! Register(atomID, cliqueID)
  }

  private def storeClique(cliqueEntry: CliqueEntry) {
    //statReceived += 1

    @inline def fetchClique(fid: Int): CliqueEntry = cliques.get(fid)

    @inline def put(fid: Int, clique: CliqueEntry) = cliques.put(fid, clique)


    @inline def addToDependencyMap(cliqueID: Int, cliqueEntry: CliqueEntry): Unit = if (createDependencyMap) {
      val clauseStats = new TIntFloatHashMap(DEFAULT_CAPACITY, DEFAULT_LOAD_FACTOR, NO_ENTRY_KEY, 0)
      clauseStats.put(cliqueEntry.clauseID, cliqueEntry.freq)
      dependencyMap.put(cliqueID, clauseStats)
    }

    val storedCliqueIDs = hashCode2CliqueIDs.get(cliqueEntry.hashKey)

    // (1) check for a stored clique with the same variables
    if (storedCliqueIDs ne null) {

      val iterator = storedCliqueIDs.iterator()
      var merged = false
      var storedId = -1
      while (iterator.hasNext && !merged) {
        storedId = iterator.next()
        val storedClique = fetchClique(storedId)

        if (jutil.Arrays.equals(storedClique.variables, cliqueEntry.variables)) {
          if (storedClique.weight != Double.PositiveInfinity) {
            // merge these cliques
            if (cliqueEntry.weight == Double.PositiveInfinity) {
              // When the stored constraint (from a previous run/iteration) is soft and
              // the new one is hard; then the resulting constraint will be hard.
              storedClique.weight = Double.PositiveInfinity
            }
            else {
              // When both stored and new constraints are soft, then merge these constraints
              storedClique.weight += cliqueEntry.weight
            }
          }
          // Otherwise, the stored constrain is hard, do not change anything and
          // thus ignore the current constraint.

          //state that a merging operation is performed.
          merged = true
          //statMerged += 1
        }
      } // while


      if (!merged) {
        // The constraint is not merged, thus we simply store it.
        put(cliqueID, cliqueEntry)
        storedCliqueIDs.add(cliqueID)

        // add to dependencyMap:
        addToDependencyMap(cliqueID, cliqueEntry)

        // next cliqueID
        cliqueID += 1
      }
      else if (createDependencyMap) {
        // Add or adjust the corresponding frequency in the dependencyMap
        dependencyMap.get(storedId).adjustOrPutValue(cliqueEntry.clauseID, cliqueEntry.freq, cliqueEntry.freq)
      }

    }
    else {
      // (2) Otherwise store this clique
      if (cliqueEntry.weight != 0) {
        put(cliqueID, cliqueEntry)
        val newEntries = new TIntArrayList()
        newEntries.add(cliqueID)
        hashCode2CliqueIDs.put(cliqueEntry.hashKey, newEntries)

        // add to dependencyMap:
        addToDependencyMap(cliqueID, cliqueEntry)

        // next cliqueID
        cliqueID += 1
      }

    }
  } // store(...)

}

private object CliqueRegisterWorker {

  def apply(index: Int, atomRegisters: PartitionedData[ActorRef], createDependencyMap: Boolean = false)(implicit master: ActorRef) =
    new CliqueRegisterWorker(index, master, atomRegisters, createDependencyMap)


}
