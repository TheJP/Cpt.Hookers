package ch.fhnw.cpthook.tools

import ch.fhnw.cpthook.viewmodel.ILevelViewModel
import ch.fhnw.ether.controller.IController
import ch.fhnw.ether.controller.event.IPointerEvent
import ch.fhnw.ether.controller.tool.AbstractTool
import ch.fhnw.ether.controller.tool.PickUtilities
import ch.fhnw.ether.controller.tool.PickUtilities.PickMode
import ch.fhnw.ether.scene.camera.ICamera
import ch.fhnw.ether.scene.I3DObject
import ch.fhnw.cpthook.model.Entity
import ch.fhnw.ether.controller.event.IKeyEvent
import com.jogamp.newt.event.KeyEvent
import ch.fhnw.ether.controller.event.IEventScheduler.IAnimationAction
import org.jbox2d.dynamics.World
import org.jbox2d.dynamics.Body
import org.jbox2d.dynamics.FixtureDef
import org.jbox2d.collision.shapes.PolygonShape
import org.jbox2d.dynamics.BodyDef
import org.jbox2d.dynamics.BodyType
import ch.fhnw.util.math.Vec3
import org.jbox2d.common.Vec2
import ch.fhnw.cpthook.model.SkyBox
import ch.fhnw.ether.scene.mesh.material.ColorMapMaterial
import ch.fhnw.cpthook.ICptHookController
import ch.fhnw.util.math.Mat4
import ch.fhnw.cpthook.SoundManager
import ch.fhnw.cpthook.model.EntitiyUpdatable
import ch.fhnw.cpthook.model.EntityActivatable
import ch.fhnw.cpthook.model.IGameStateChanger
import ch.fhnw.cpthook.model.IGameStateController
import ch.fhnw.ether.ui.Button
import ch.fhnw.ether.ui.Button.IButtonAction
import ch.fhnw.ether.view.IView
import ch.fhnw.cpthook.EtherHacks

/**
 * Tool, which handles the game logic.
 */
class GameTool(val controller: ICptHookController, val camera: ICamera, val viewModel: ILevelViewModel)
  extends AbstractTool(controller) with IAnimationAction with IGameStateController {
  
  val inputManager = controller.inputManager
  val world: World = new World(new org.jbox2d.common.Vec2(0.0f, -40.0f))
  val gameContactListener = new GameContactListener
  var follow = true
  val skyBox = new SkyBox().createMesh()
  var skyBoxOffsetX = 0.0
  var skyBoxOffsetY = 0.0
  var lastX = 0.0
  var lastY = 0.0
  
  var updateableEntites = List[EntitiyUpdatable]()
  var activatableEntites = List[EntityActivatable]()
  var stateChangerEntities = List[IGameStateChanger]()

  override def activate(): Unit = {

    //Switch sounds
    SoundManager.playSound(SoundManager.LevelSound, 0.2f, true, false)

    //SoundManager.playSong(SoundManager.Level)

    // link all entities to box2D
    viewModel.entities.foreach(_._1.linkBox2D(world));

    // link player to box2D
    viewModel.getPlayer.linkBox2D(world)

    (viewModel.entities.keys ++ Iterable(viewModel.getPlayer)) foreach { entity =>
      if (entity.isInstanceOf[EntitiyUpdatable]) {
        updateableEntites ::= entity.asInstanceOf[EntitiyUpdatable]
      }
      if (entity.isInstanceOf[EntityActivatable]) {
        activatableEntites ::= entity.asInstanceOf[EntityActivatable]
      }
      if (entity.isInstanceOf[IGameStateChanger]) {
        stateChangerEntities ::= entity.asInstanceOf[IGameStateChanger]
      }
    }
    
    world.setContactListener(gameContactListener)

    stateChangerEntities foreach { _.init(this) }
    activatableEntites foreach { _.activate(gameContactListener) }

    //Skybox
    viewModel.addSkyBox(skyBox)
    lastX = viewModel.getPlayer.mesh.getPosition.x
    lastY = viewModel.getPlayer.mesh.getPosition.y
    
    controller.animate(this)
    
    setupUI()
  }
  
  def setupUI(): Unit = {
    
    var switchModeButton = new Button(0, 0, "Edit", "(M) Switches to edit mode", KeyEvent.VK_M, new IButtonAction() {
      def execute(button: Button, view: IView) = {
        EtherHacks.removeWidgets(controller)
        controller.setCurrentTool(new EditorTool(controller, camera, viewModel)) 
      }
    })
    
    controller.getUI.addWidget(switchModeButton)
  }
  
  override def deactivate(): Unit = {
    SoundManager.stopAll()
    viewModel.removeSkyBox(skyBox)
    controller.kill(this)
    activatableEntites.foreach { _.deactivate() }
  }

  def run(time: Double, interval: Double) : Unit = {
    world.step(interval.toFloat, GameTool.VelocityIterations, GameTool.PositionIterations)
    
    updateableEntites.foreach { _.update(inputManager, time) }
    
    updateCamera
    updateSkyBox
   
    inputManager.clearWasPressed
  }
  
  def updateCamera: Unit = {
    camera.setTarget(viewModel.getPlayer.mesh.getPosition)
    if(follow) {
      camera.setPosition(viewModel.getPlayer.mesh.getPosition.add(new Vec3(0, 0, 20)))
    }   
  }
  
  def updateSkyBox: Unit = {
    if (follow) {
      skyBoxOffsetX += ((viewModel.getPlayer.mesh.getPosition.x - lastX) * 0.5)
      skyBoxOffsetY += ((viewModel.getPlayer.mesh.getPosition.y - lastY) * 0.5)
      lastX = viewModel.getPlayer.mesh.getPosition.x
      lastY = viewModel.getPlayer.mesh.getPosition.y
      skyBox.setPosition(viewModel.getPlayer.mesh.getPosition.subtract(new Vec3(skyBoxOffsetX, skyBoxOffsetY, 20)))  
      if(skyBoxOffsetX > 60) skyBoxOffsetX = -60
      else if(skyBoxOffsetX < -60) skyBoxOffsetX = 60  
      if(skyBoxOffsetY > 60) skyBoxOffsetY = -60
      else if(skyBoxOffsetY < -60) skyBoxOffsetY = 60 
    }
  }
 
  override def keyPressed(event: IKeyEvent): Unit = event.getKeyCode match {
      
    case KeyEvent.VK_G =>
      world.setGravity(world.getGravity.mul(-1f))
      
    case KeyEvent.VK_F =>
      follow = !follow
      if(!follow) { camera.setPosition(new Vec3(0, 0, 20)) }
      
    case _ =>

  }

  //Game Over and win sounds?
  def isActive = controller.getCurrentTool == this
  def gameOver: Unit = if(isActive){ controller.setCurrentTool(new GameTool(controller, camera, viewModel)) }
  def killMonser(body: Body): Unit = println("Kill monster") //TODO:
  def win: Unit = if(isActive){ controller.setCurrentTool(new EditorTool(controller, camera, viewModel)) }

}

object GameTool {
  val VelocityIterations: Int = 6
  val PositionIterations: Int = 3
}