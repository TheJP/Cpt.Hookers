package ch.fhnw.cpthook.tools

import com.jogamp.newt.event.KeyEvent
import ch.fhnw.cpthook.Defaults
import ch.fhnw.cpthook.model.Block
import ch.fhnw.cpthook.model.Position
import ch.fhnw.cpthook.model.Size
import ch.fhnw.cpthook.model.Vec2.toVec3
import ch.fhnw.cpthook.viewmodel.ILevelViewModel
import ch.fhnw.ether.controller.IController
import ch.fhnw.ether.controller.event.IKeyEvent
import ch.fhnw.ether.controller.event.IPointerEvent
import ch.fhnw.ether.controller.tool.AbstractTool
import ch.fhnw.ether.controller.tool.PickUtilities
import ch.fhnw.ether.controller.tool.PickUtilities.PickMode
import ch.fhnw.ether.scene.I3DObject
import ch.fhnw.ether.scene.camera.ICamera
import ch.fhnw.ether.view.ProjectionUtilities
import ch.fhnw.util.math.Vec3
import ch.fhnw.ether.scene.camera.IViewCameraState
import com.sun.xml.internal.bind.annotation.OverrideAnnotationOf
import ch.fhnw.cpthook.model.Npo
import javax.swing.JFileChooser
import ch.fhnw.cpthook.json.JsonSerializer
import ch.fhnw.cpthook.ICptHookController
import ch.fhnw.ether.scene.mesh.IMesh
import ch.fhnw.cpthook.model.Block
import ch.fhnw.cpthook.model.Position
import ch.fhnw.ether.scene.mesh.DefaultMesh

/**
 * Tool, which is used in the editor.
 * Responsible for movement and level changes (e.g. block adding).
 */
class EditorTool(val controller: ICptHookController, val camera: ICamera, val viewModel: ILevelViewModel)
  extends AbstractTool(controller) {

  val OffsetScale = 0.2f
  var offsetX: Float = 0
  var startX: Float = 0
  var offsetZ: Float = 20

  //Factories
  val blockFactory = (position: Position, size: Size) => new Block(position, size);

  //Tuples with npo factories and meshes
  val editorMeshes = List(
      (blockFactory, blockFactory(Position(0, 0),  Size(1, 1)).to3DObject)
  );

  object EditingState extends Enumeration { val Adding, Removing = Value }
  /** Determines, if the user is currently adding or removing elements. */
  var editingState = EditingState.Adding
  
  camera.setUp(Defaults.cameraUp)

  override def activate = {
    editorMeshes.map(_._2).foreach { mesh => controller.getScene.add3DObject(mesh) }
    updateCamera
  }

  override def deactivate = {
    editorMeshes.map(_._2).foreach { mesh => controller.getScene.remove3DObject(mesh) }
    updateCamera
  }

  /**
   * Sets the camera position and value to the current offset information.
   */
  private def updateCamera : Unit = {
    camera.setTarget(new Vec3(offsetX, 0, 1))
    camera.setPosition(new Vec3(offsetX, 0, offsetZ))
    updateGuiPositions
  }

  /**
   * Update gui component positions.
   * So they seem fixed relative to the camera.
   */
  private def updateGuiPositions : Unit = {
    if(controller.getViews.isEmpty){ return }
    val view = controller.getViews.get(0)
    val viewport = view.getViewport
    val viewCameraState = getController.getRenderManager.getViewCameraState(view)
    val ray = ProjectionUtilities.getRay(viewCameraState, viewport.w / 2.0f, viewport.h / 2.0f)
    // check if we can hit the xy plane
    if(ray.getDirection.z != 0f) {
      val s: Float = -ray.getOrigin.z / ray.getDirection.z
      val p: Vec3 = ray.getOrigin add ray.getDirection.scale(s)
      editorMeshes.map(_._2).foreach { mesh =>
        mesh.setPosition(new Vec3(offsetX, 0, offsetZ / 2))
      }
      println(offsetX - p.x)
    }
    //TODO
  }

  /**
   * Remove nearest clicked block. Returns true if a block was removed and false otherwise.
   */
  private def remove(event: IPointerEvent)(implicit viewCameraState: IViewCameraState): Boolean = {
    val npo = findNearest(event)
    if(npo != null){ viewModel.removeNpo(npo) }
    return npo != null
  }

  /**
   * Finds nearest clicked block. If no block was clicked null is returned.
   */
  private def findNearest(event: IPointerEvent)(implicit viewCameraState: IViewCameraState): Npo = {
    val hitDistance = (mesh: I3DObject) => PickUtilities.pickObject(PickMode.POINT, event.getX, event.getY, 0, 0, viewCameraState, mesh)
    val hits = viewModel.npos
      .map(npo => (hitDistance(npo._2) -> npo._1))
      .filter(_._1 < Float.PositiveInfinity)
    if(!hits.isEmpty) { return hits.minBy(_._1)._2 }
    return null
  }

  /**
   * Adds a block at the pointer position if possible.
   */
  private def add(event: IPointerEvent)(implicit viewCameraState: IViewCameraState): Unit = {
    val ray = ProjectionUtilities.getRay(viewCameraState, event.getX, event.getY)
    // check if we can hit the xy plane
    if(ray.getDirection.z != 0f) {
      val s: Float = -ray.getOrigin.z / ray.getDirection.z
      val p: Vec3 = ray.getOrigin add ray.getDirection.scale(s)
      val size = new Size(1, 1)
      viewModel.addNpo(new Block(new Position((p.x - size.x / 2).round.toInt, (p.y - size.y / 2).round.toInt), size))
    }
  }
  
  override def pointerPressed(event: IPointerEvent): Unit = event.getButton match {
    case IPointerEvent.BUTTON_2 | IPointerEvent.BUTTON_3 =>
      startX = event.getX
    case IPointerEvent.BUTTON_1 =>
      implicit val viewCameraState = getController.getRenderManager.getViewCameraState(event.getView)
      val removed = remove(event)
      if(!removed){ add(event) }
      editingState = if(removed) EditingState.Removing else EditingState.Adding
    case default => //Unkown key
  }

  /**
   * Moves the view horizontally in the editor mode.
   */
  override def pointerDragged(event: IPointerEvent): Unit = event.getButton match {
    case IPointerEvent.BUTTON_2 | IPointerEvent.BUTTON_3 =>
      val delta = event.getX - startX
      offsetX += delta * OffsetScale
      updateCamera
      startX = event.getX
    case IPointerEvent.BUTTON_1 =>
      implicit val viewCameraState = getController.getRenderManager.getViewCameraState(event.getView)
      if(editingState == EditingState.Adding){
        if(findNearest(event) == null){ add(event) }
      } else {
        remove(event)
      }
    case default => //Unknown key
  }

  override def pointerMoved(event: IPointerEvent): Unit = {
    //TODO: Ghost block
  }
  
  override def pointerScrolled(event: IPointerEvent): Unit = {
    offsetZ += event.getScrollY * OffsetScale
    updateCamera
  }

  override def keyPressed(event: IKeyEvent): Unit = event.getKeyCode match {
    case KeyEvent.VK_M =>
      controller.setCurrentTool(new GameTool(controller, camera, viewModel))
    case KeyEvent.VK_S if event.isControlDown =>
      val fileChooser = new JFileChooser
      fileChooser.setDialogTitle("Save Level")
      if(fileChooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION){
        viewModel.saveLevel(fileChooser.getSelectedFile.getAbsolutePath)
      }
    case KeyEvent.VK_O if event.isControlDown =>
      val fileChooser = new JFileChooser
      fileChooser.setDialogTitle("Open Level")
      if(fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION){
        viewModel.openLevel(fileChooser.getSelectedFile.getAbsolutePath)
      }
    case default => //Unknown key
  }

}