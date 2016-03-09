package core.subdomainscanner

import akka.actor._
import core.subdomainscanner.DispatcherMessage._
import core.subdomainscanner.ListenerMessage._
import core.subdomainscanner.ScannerMessage.{Scan, ScanAvailable}

object Dispatcher {
  def props(listener: ActorRef, hostname: String, threads: Int, subdomains: List[String], resolvers: List[String]): Props =
    Props(new Dispatcher(listener, hostname: String, threads, subdomains, resolvers))

  private def createScanners(context: ActorContext, listener: ActorRef, threads: Int, hostname: String): Set[ActorRef] =
    Vector.fill(threads) {
      val scanner = context.actorOf(Scanner.props(listener, hostname))
      context.watch(scanner)
      scanner
    }.toSet
}

class Dispatcher(listener: ActorRef,
                 hostname: String,
                 threads: Int,
                 subdomains: List[String],
                 resolvers: List[String]) extends Actor {

  var master: Option[ActorRef] = None

  var pauseScanning = false
  var numberOfPausedScanners = 0
  var whoToNotifyAboutPaused: Option[ActorRef] = None

  val dispatcherQueue: DispatcherQueue = DispatcherQueue.create(subdomains, resolvers)

  var scansSoFar: Int = 0
  var scansInTotal: Int = subdomains.size

  var currentlyScanning: Set[String] = Set.empty

  var scannerRefs: Set[ActorRef] = Dispatcher.createScanners(context, listener, threads, hostname)
  scannerRefs.foreach(_ ! ScanAvailable)

  def receive = {
    case ResumeScanning =>
      scanningHasResumed()
      scannerRefs.foreach(_ ! ScanAvailable)
      listener ! ResumedScanning

    case PauseScanning =>
      scanningHasPaused()
      whoToNotifyAboutPaused = Some(sender)
      listener ! PausingScanning

    case CompletedScan(subdomain, resolver) =>
      subdomainHasBeenScanned(subdomain)
      dispatcherQueue.recycleResolver(resolver)
      scannerIsAvailableToScan(sender)

    case AvailableForScan =>
      scannerIsAvailableToScan(sender)

    case NotifyOnCompletion =>
      master = Some(sender)

    case PriorityScanSubdomain(subdomain: String) =>
      dispatcherQueue.enqueuePrioritySubdomain(subdomain)

    case Terminated(scanner) =>
      scannerHasTerminated(scanner)

      if (scanningHasNotBeenPaused && allScannersHaveTerminated) {
        if (allSubdomainsHaveBeenScanned) {
          if (master.isDefined) master.get ! None
          else listener ! PrintError("The dispatcher doesn't know who to notify of completion! Terminating anyway.")

          context.system.terminate()
        } else {
          // Add any missed subdomains back to the queue
          currentlyScanning.foreach(_ => dispatcherQueue.requeueSubdomain(_))
          currentlyScanning = Set.empty

          // Start scanning again.
          val numberOfScannersToCreate: Int =
            Array(dispatcherQueue.remainingNumberOfSubdomains,
              dispatcherQueue.remainingNumberOfResolvers,
              threads).min

          scannerRefs = Dispatcher.createScanners(context, listener, numberOfScannersToCreate, hostname)
          scannerRefs.foreach(_ ! ScanAvailable)
        }
      }
  }

  def scannerIsAvailableToScan(scanner: ActorRef) = {
    if (scanningHasBeenPaused) {
      // Don't send anything to the scanner, consider it paused
      aScannerHasBeenPaused()
    }
    else if (dispatcherQueue.isOutOfSubdomains) {
      // There aren't any subdomains for this scanner. Stop this scanner from working
      context.stop(sender)
    }
    else if (!dispatcherQueue.isOutOfResolvers) {
      val resolver = dispatcherQueue.dequeueResolver()
      val subdomain = dispatcherQueue.dequeueSubdomain()

      doScan(sender, subdomain, resolver)

    } else {
      // We don't have enough resolvers to go around. Stop this scanner from working
      listener ! PrintWarning(s"There aren't enough resolvers for each thread. Reducing thread count by 1.")
      terminateScanner(scanner)
    }
  }

  def doScan(ref: ActorRef, subdomain: String, resolver: String) = {
    scanningSubdomain(subdomain)
    ref ! Scan(subdomain, resolver)
    scansSoFar += 1
    listener ! LastScan(subdomain, scansSoFar, scansInTotal)
  }

  // Keeping track of subdomains currently being scanned
  def subdomainHasBeenScanned(subdomain: String) = currentlyScanning = currentlyScanning.diff(Set(subdomain))
  def scanningSubdomain(subdomain: String) = currentlyScanning = currentlyScanning ++ Set(subdomain)
  def allSubdomainsHaveBeenScanned = dispatcherQueue.isOutOfSubdomains && currentlyScanning.isEmpty

  // Keeping track of scanning and whether it's been paused
  def scanningHasBeenPaused: Boolean = pauseScanning
  def scanningHasNotBeenPaused: Boolean = !pauseScanning
  def aScannerHasBeenPaused() = {
    numberOfPausedScanners += 1

    if (allScannersHaveBeenPaused && whoToNotifyAboutPaused.isDefined)
      whoToNotifyAboutPaused.get ! true
  }
  def allScannersHaveBeenPaused: Boolean = numberOfPausedScanners == scannerRefs.size
  def scanningHasPaused() = pauseScanning = true
  def scanningHasResumed() = {
    pauseScanning = false
    numberOfPausedScanners = 0
  }

  // Keeping track of scanner references
  def scannerHasTerminated(scanner: ActorRef) = scannerRefs = scannerRefs.diff(Set(scanner))
  def terminateScanner(scanner: ActorRef) = context.stop(scanner)
  def allScannersHaveTerminated = scannerRefs.isEmpty
}