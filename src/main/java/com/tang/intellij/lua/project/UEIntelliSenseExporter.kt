/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.project

import com.intellij.openapi.project.Project
import java.io.File
import java.io.IOException

/**
 * UE IntelliSense exporter that generates Lua code hint files
 * by analyzing UE project structure and C++ headers
 * Based on UnLua's IntelliSense generation system
 */
class UEIntelliSenseExporter {
    
    companion object {
        private const val UE_LUA_FILENAME = "UE.lua"
        private const val UNLUA_LUA_FILENAME = "UnLua.lua"
        private const val UE_INTELLISENSE_FOLDER = "UELuaIntelliSense"
        
        // Lua keywords that need escaping
        private val LUA_KEYWORDS = setOf("local", "function", "end", "if", "then", "else", "elseif", 
            "while", "for", "do", "repeat", "until", "break", "return", "nil", "true", "false",
            "and", "or", "not", "in")
            
        // Property type mappings from UE to Lua types
        private val TYPE_MAPPINGS = mapOf(
            "int8" to "integer", "int16" to "integer", "int32" to "integer", "int64" to "integer",
            "uint8" to "integer", "uint16" to "integer", "uint32" to "integer", "uint64" to "integer",
            "float" to "number", "double" to "number",
            "bool" to "boolean",
            "FName" to "string", "FString" to "string", "FText" to "string"
        )
    }
    
    /**
     * Generate UE IntelliSense files for the given project
     */
    fun generateIntelliSenseFiles(project: Project, ideaFolder: File, ueProjectPath: String? = null): Boolean {
        return try {
            // Create UELuaIntelliSense subdirectory
            val ueIntelliSenseFolder = File(ideaFolder, UE_INTELLISENSE_FOLDER)
            if (!ueIntelliSenseFolder.exists()) {
                ueIntelliSenseFolder.mkdirs()
            }
            
            generateUELuaFile(ueIntelliSenseFolder, ueProjectPath)
            generateUnLuaFile(ueIntelliSenseFolder, ueProjectPath)
            
            // Add the folder to project libraries
            addToProjectLibraries(project, ueIntelliSenseFolder)
            
            true
        } catch (e: Exception) {
            throw IOException("Failed to generate UE IntelliSense files: ${e.message}", e)
        }
    }
    
    /**
     * Generate UE.lua file with core UE types and functions
     */
    private fun generateUELuaFile(ideaFolder: File, ueProjectPath: String?) {
        val ueFile = File(ideaFolder, UE_LUA_FILENAME)
        val content = buildUELuaContent(ueProjectPath)
        ueFile.writeText(content)
    }
    
    /**
     * Generate UnLua.lua file with UnLua specific bindings
     */
    private fun generateUnLuaFile(ideaFolder: File, ueProjectPath: String?) {
        val unLuaFile = File(ideaFolder, UNLUA_LUA_FILENAME)
        val content = buildUnLuaContent(ueProjectPath)
        unLuaFile.writeText(content)
    }
    
    /**
     * Build UE.lua content by analyzing UE project structure
     * Based on UnLua's reflection scanning approach
     */
    private fun buildUELuaContent(ueProjectPath: String?): String {
        val builder = StringBuilder()
        
        // Meta annotation
        builder.appendLine("---@meta")
        builder.appendLine()
        
        // Core UE namespace
        builder.appendLine("---@class UE")
        builder.appendLine("UE = {}")
        builder.appendLine()
        
        // Export engine reflection types (equivalent to CollectTypes in UnLua)
        exportEngineReflectionTypes(builder, ueProjectPath)
        
        // Export UE utility functions
        exportUEUtilityFunctions(builder)
        
        builder.appendLine()
        builder.appendLine("return UE")
        
        return builder.toString()
    }
    
    /**
     * Build UnLua.lua content
     */
    private fun buildUnLuaContent(ueProjectPath: String?): String {
        val builder = StringBuilder()
        
        // Meta annotation
        builder.appendLine("---@meta")
        builder.appendLine()
        
        // UnLua namespace
        builder.appendLine("---@class UnLua")
        builder.appendLine("UnLua = {}")
        builder.appendLine()
        
        // Add UnLua interface
        addUnLuaInterface(builder)
        
        // Add UnLua functions
        addUnLuaFunctions(builder)
        
        builder.appendLine()
        builder.appendLine("return UnLua")
        
        return builder.toString()
    }
    
    /**
     * Add core UE types (UObject, AActor, etc.)
     */
    private fun addCoreUETypes(builder: StringBuilder) {
        // UObject hierarchy
        builder.appendLine("---@class UObject")
        builder.appendLine("---@field public Class UClass")
        builder.appendLine("local UObject = {}")
        builder.appendLine()
        
        builder.appendLine("---@class UClass : UObject")
        builder.appendLine("local UClass = {}")
        builder.appendLine()
        
        builder.appendLine("---@class UStruct : UObject")
        builder.appendLine("local UStruct = {}")
        builder.appendLine()
        
        builder.appendLine("---@class UScriptStruct : UStruct")
        builder.appendLine("local UScriptStruct = {}")
        builder.appendLine()
        
        builder.appendLine("---@class UFunction : UStruct")
        builder.appendLine("local UFunction = {}")
        builder.appendLine()
        
        builder.appendLine("---@class UEnum : UObject")
        builder.appendLine("local UEnum = {}")
        builder.appendLine()
        
        // Actor hierarchy
        builder.appendLine("---@class AActor : UObject")
        builder.appendLine("---@field public RootComponent USceneComponent")
        builder.appendLine("---@field public Tags string[]")
        builder.appendLine("---@field public bHidden boolean")
        builder.appendLine("---@field public bReplicates boolean")
        builder.appendLine("local AActor = {}")
        builder.appendLine()
        
        // Component hierarchy
        builder.appendLine("---@class UActorComponent : UObject")
        builder.appendLine("---@field public ComponentTags string[]")
        builder.appendLine("---@field public bIsActive boolean")
        builder.appendLine("---@field public PrimaryComponentTick FTickFunction")
        builder.appendLine("local UActorComponent = {}")
        builder.appendLine()
        
        builder.appendLine("---@class USceneComponent : UActorComponent")
        builder.appendLine("---@field public ComponentLocation FVector")
        builder.appendLine("---@field public ComponentRotation FRotator")
        builder.appendLine("---@field public ComponentScale FVector")
        builder.appendLine("---@field public AttachParent USceneComponent")
        builder.appendLine("---@field public AttachChildren USceneComponent[]")
        builder.appendLine("local USceneComponent = {}")
        builder.appendLine()
        
        builder.appendLine("---@class UPrimitiveComponent : USceneComponent")
        builder.appendLine("---@field public BodyInstance FBodyInstance")
        builder.appendLine("---@field public bGenerateOverlapEvents boolean")
        builder.appendLine("local UPrimitiveComponent = {}")
        builder.appendLine()
        
        builder.appendLine("---@class UMeshComponent : UPrimitiveComponent")
        builder.appendLine("---@field public Materials UMaterialInterface[]")
        builder.appendLine("local UMeshComponent = {}")
        builder.appendLine()
        
        builder.appendLine("---@class UStaticMeshComponent : UMeshComponent")
        builder.appendLine("---@field public StaticMesh UStaticMesh")
        builder.appendLine("local UStaticMeshComponent = {}")
        builder.appendLine()
        
        builder.appendLine("---@class USkeletalMeshComponent : UMeshComponent")
        builder.appendLine("---@field public SkeletalMesh USkeletalMesh")
        builder.appendLine("---@field public AnimationMode EAnimationMode")
        builder.appendLine("local USkeletalMeshComponent = {}")
        builder.appendLine()
        
        builder.appendLine("---@class UCapsuleComponent : UPrimitiveComponent")
        builder.appendLine("---@field public CapsuleHalfHeight number")
        builder.appendLine("---@field public CapsuleRadius number")
        builder.appendLine("local UCapsuleComponent = {}")
        builder.appendLine()
        
        // Pawn and Character
        builder.appendLine("---@class APawn : AActor")
        builder.appendLine("---@field public Controller AController")
        builder.appendLine("---@field public PlayerState APlayerState")
        builder.appendLine("---@field public bUseControllerRotationYaw boolean")
        builder.appendLine("---@field public bUseControllerRotationPitch boolean")
        builder.appendLine("---@field public bUseControllerRotationRoll boolean")
        builder.appendLine("local APawn = {}")
        builder.appendLine()
        
        builder.appendLine("---@class ACharacter : APawn")
        builder.appendLine("---@field public CharacterMovement UCharacterMovementComponent")
        builder.appendLine("---@field public CapsuleComponent UCapsuleComponent")
        builder.appendLine("---@field public Mesh USkeletalMeshComponent")
        builder.appendLine("---@field public bIsCrouched boolean")
        builder.appendLine("---@field public JumpCurrentCount integer")
        builder.appendLine("---@field public JumpMaxCount integer")
        builder.appendLine("local ACharacter = {}")
        builder.appendLine()
        
        // Controllers
        builder.appendLine("---@class AController : AActor")
        builder.appendLine("---@field public Pawn APawn")
        builder.appendLine("---@field public PlayerState APlayerState")
        builder.appendLine("local AController = {}")
        builder.appendLine()
        
        builder.appendLine("---@class APlayerController : AController")
        builder.appendLine("---@field public PlayerCameraManager APlayerCameraManager")
        builder.appendLine("---@field public HUD AHUD")
        builder.appendLine("local APlayerController = {}")
        builder.appendLine()
        
        builder.appendLine("---@class AAIController : AController")
        builder.appendLine("---@field public BrainComponent UBrainComponent")
        builder.appendLine("local AAIController = {}")
        builder.appendLine()
        
        // Movement component
        builder.appendLine("---@class UCharacterMovementComponent : UActorComponent")
        builder.appendLine("---@field public MaxWalkSpeed number")
        builder.appendLine("---@field public JumpZVelocity number")
        builder.appendLine("---@field public GravityScale number")
        builder.appendLine("---@field public GroundFriction number")
        builder.appendLine("---@field public MaxAcceleration number")
        builder.appendLine("---@field public BrakingDecelerationWalking number")
        builder.appendLine("---@field public bOrientRotationToMovement boolean")
        builder.appendLine("local UCharacterMovementComponent = {}")
        builder.appendLine()
        
        // Game framework
        builder.appendLine("---@class AGameModeBase : AActor")
        builder.appendLine("---@field public DefaultPawnClass UClass")
        builder.appendLine("---@field public PlayerControllerClass UClass")
        builder.appendLine("local AGameModeBase = {}")
        builder.appendLine()
        
        builder.appendLine("---@class AGameStateBase : AActor")
        builder.appendLine("---@field public AuthorityGameMode AGameModeBase")
        builder.appendLine("local AGameStateBase = {}")
        builder.appendLine()
        
        builder.appendLine("---@class APlayerState : AActor")
        builder.appendLine("---@field public PlayerName string")
        builder.appendLine("---@field public PlayerId integer")
        builder.appendLine("local APlayerState = {}")
        builder.appendLine()
        
        // Interface
        builder.appendLine("---@class UInterface : UObject")
        builder.appendLine("local UInterface = {}")
        builder.appendLine()
        
        // Math types
        addMathTypes(builder)
        
        // Container types
        addContainerTypes(builder)
    }
    
    /**
     * Add UE math types (FVector, FRotator, etc.)
     */
    private fun addMathTypes(builder: StringBuilder) {
        builder.appendLine("---@class FVector")
        builder.appendLine("---@field public X number")
        builder.appendLine("---@field public Y number")
        builder.appendLine("---@field public Z number")
        builder.appendLine("local FVector = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FVector2D")
        builder.appendLine("---@field public X number")
        builder.appendLine("---@field public Y number")
        builder.appendLine("local FVector2D = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FVector4")
        builder.appendLine("---@field public X number")
        builder.appendLine("---@field public Y number")
        builder.appendLine("---@field public Z number")
        builder.appendLine("---@field public W number")
        builder.appendLine("local FVector4 = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FRotator")
        builder.appendLine("---@field public Pitch number")
        builder.appendLine("---@field public Yaw number")
        builder.appendLine("---@field public Roll number")
        builder.appendLine("local FRotator = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FQuat")
        builder.appendLine("---@field public X number")
        builder.appendLine("---@field public Y number")
        builder.appendLine("---@field public Z number")
        builder.appendLine("---@field public W number")
        builder.appendLine("local FQuat = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FTransform")
        builder.appendLine("---@field public Translation FVector")
        builder.appendLine("---@field public Rotation FQuat")
        builder.appendLine("---@field public Scale3D FVector")
        builder.appendLine("local FTransform = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FMatrix")
        builder.appendLine("---@field public M table")
        builder.appendLine("local FMatrix = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FLinearColor")
        builder.appendLine("---@field public R number")
        builder.appendLine("---@field public G number")
        builder.appendLine("---@field public B number")
        builder.appendLine("---@field public A number")
        builder.appendLine("local FLinearColor = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FColor")
        builder.appendLine("---@field public R integer")
        builder.appendLine("---@field public G integer")
        builder.appendLine("---@field public B integer")
        builder.appendLine("---@field public A integer")
        builder.appendLine("local FColor = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FIntPoint")
        builder.appendLine("---@field public X integer")
        builder.appendLine("---@field public Y integer")
        builder.appendLine("local FIntPoint = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FIntVector")
        builder.appendLine("---@field public X integer")
        builder.appendLine("---@field public Y integer")
        builder.appendLine("---@field public Z integer")
        builder.appendLine("local FIntVector = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FBox")
        builder.appendLine("---@field public Min FVector")
        builder.appendLine("---@field public Max FVector")
        builder.appendLine("---@field public IsValid boolean")
        builder.appendLine("local FBox = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FSphere")
        builder.appendLine("---@field public Center FVector")
        builder.appendLine("---@field public W number")
        builder.appendLine("local FSphere = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FPlane")
        builder.appendLine("---@field public X number")
        builder.appendLine("---@field public Y number")
        builder.appendLine("---@field public Z number")
        builder.appendLine("---@field public W number")
        builder.appendLine("local FPlane = {}")
        builder.appendLine()
    }
    
    /**
     * Add UE container types (TArray, TMap, etc.)
     */
    private fun addContainerTypes(builder: StringBuilder) {
        builder.appendLine("---@class TArray")
        builder.appendLine("---@field public Num integer")
        builder.appendLine("local TArray = {}")
        builder.appendLine()
        
        builder.appendLine("---@class TMap")
        builder.appendLine("---@field public Num integer")
        builder.appendLine("local TMap = {}")
        builder.appendLine()
        
        builder.appendLine("---@class TSet")
        builder.appendLine("---@field public Num integer")
        builder.appendLine("local TSet = {}")
        builder.appendLine()
        
        builder.appendLine("---@class TSubclassOf")
        builder.appendLine("local TSubclassOf = {}")
        builder.appendLine()
        
        builder.appendLine("---@class TSoftObjectPtr")
        builder.appendLine("local TSoftObjectPtr = {}")
        builder.appendLine()
        
        builder.appendLine("---@class TSoftClassPtr")
        builder.appendLine("local TSoftClassPtr = {}")
        builder.appendLine()
        
        builder.appendLine("---@class TWeakObjectPtr")
        builder.appendLine("local TWeakObjectPtr = {}")
        builder.appendLine()
        
        builder.appendLine("---@class TLazyObjectPtr")
        builder.appendLine("local TLazyObjectPtr = {}")
        builder.appendLine()
        
        builder.appendLine("---@class TScriptInterface")
        builder.appendLine("local TScriptInterface = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FTickFunction")
        builder.appendLine("---@field public bCanEverTick boolean")
        builder.appendLine("---@field public bStartWithTickEnabled boolean")
        builder.appendLine("local FTickFunction = {}")
        builder.appendLine()
        
        builder.appendLine("---@class FBodyInstance")
        builder.appendLine("---@field public bSimulatePhysics boolean")
        builder.appendLine("---@field public bEnableGravity boolean")
        builder.appendLine("local FBodyInstance = {}")
        builder.appendLine()
        
        builder.appendLine("---@class Delegate")
        builder.appendLine("local Delegate = {}")
        builder.appendLine()
        
        builder.appendLine("---@class MulticastDelegate")
        builder.appendLine("local MulticastDelegate = {}")
        builder.appendLine()
    }
    
    /**
     * Add common UE functions
     */
    private fun addUEFunctions(builder: StringBuilder) {
        builder.appendLine("-- Common UE functions")
        
        // Object creation functions
        builder.appendLine("---Create a new UObject instance")
        builder.appendLine("---@param ObjectClass UClass")
        builder.appendLine("---@param Outer UObject?")
        builder.appendLine("---@param Name string?")
        builder.appendLine("---@return UObject")
        builder.appendLine("function UE.NewObject(ObjectClass, Outer, Name) end")
        builder.appendLine()
        
        builder.appendLine("---Create a new UObject instance with template")
        builder.appendLine("---@param ObjectClass UClass")
        builder.appendLine("---@param Outer UObject?")
        builder.appendLine("---@param Name string?")
        builder.appendLine("---@param Template UObject?")
        builder.appendLine("---@return UObject")
        builder.appendLine("function UE.NewObjectWithTemplate(ObjectClass, Outer, Name, Template) end")
        builder.appendLine()
        
        builder.appendLine("---Duplicate an object")
        builder.appendLine("---@param SourceObject UObject")
        builder.appendLine("---@param Outer UObject?")
        builder.appendLine("---@param Name string?")
        builder.appendLine("---@return UObject")
        builder.appendLine("function UE.DuplicateObject(SourceObject, Outer, Name) end")
        builder.appendLine()
        
        // Class loading functions
        builder.appendLine("---Load a class by path")
        builder.appendLine("---@param Path string")
        builder.appendLine("---@return UClass?")
        builder.appendLine("function UE.LoadClass(Path) end")
        builder.appendLine()
        
        builder.appendLine("---Find a class by name")
        builder.appendLine("---@param ClassName string")
        builder.appendLine("---@return UClass?")
        builder.appendLine("function UE.FindClass(ClassName) end")
        builder.appendLine()
        
        builder.appendLine("---Get class by name")
        builder.appendLine("---@param ClassName string")
        builder.appendLine("---@return UClass?")
        builder.appendLine("function UE.GetClass(ClassName) end")
        builder.appendLine()
        
        // Object loading functions
        builder.appendLine("---Load an object by path")
        builder.appendLine("---@param Class UClass")
        builder.appendLine("---@param Path string")
        builder.appendLine("---@return UObject?")
        builder.appendLine("function UE.LoadObject(Class, Path) end")
        builder.appendLine()
        
        builder.appendLine("---Load an asset by path")
        builder.appendLine("---@param Path string")
        builder.appendLine("---@return UObject?")
        builder.appendLine("function UE.LoadAsset(Path) end")
        builder.appendLine()
        
        // Find functions
        builder.appendLine("---Find an object by name")
        builder.appendLine("---@param Name string")
        builder.appendLine("---@return UObject?")
        builder.appendLine("function UE.FindObject(Name) end")
        builder.appendLine()
        
        builder.appendLine("---Find an object by class and name")
        builder.appendLine("---@param Class UClass")
        builder.appendLine("---@param Name string")
        builder.appendLine("---@return UObject?")
        builder.appendLine("function UE.FindObjectByClass(Class, Name) end")
        builder.appendLine()
        
        builder.appendLine("---Find all objects of class")
        builder.appendLine("---@param Class UClass")
        builder.appendLine("---@return UObject[]")
        builder.appendLine("function UE.FindObjectsOfClass(Class) end")
        builder.appendLine()
        
        // World functions
        builder.appendLine("---Get the current world")
        builder.appendLine("---@return UWorld?")
        builder.appendLine("function UE.GetWorld() end")
        builder.appendLine()
        
        builder.appendLine("---Get the game instance")
        builder.appendLine("---@return UGameInstance?")
        builder.appendLine("function UE.GetGameInstance() end")
        builder.appendLine()
        
        builder.appendLine("---Get the player controller")
        builder.appendLine("---@param PlayerIndex integer?")
        builder.appendLine("---@return APlayerController?")
        builder.appendLine("function UE.GetPlayerController(PlayerIndex) end")
        builder.appendLine()
        
        builder.appendLine("---Get the player pawn")
        builder.appendLine("---@param PlayerIndex integer?")
        builder.appendLine("---@return APawn?")
        builder.appendLine("function UE.GetPlayerPawn(PlayerIndex) end")
        builder.appendLine()
        
        // Reflection functions
        builder.appendLine("---Get property value")
        builder.appendLine("---@param Object UObject")
        builder.appendLine("---@param PropertyName string")
        builder.appendLine("---@return any")
        builder.appendLine("function UE.GetProperty(Object, PropertyName) end")
        builder.appendLine()
        
        builder.appendLine("---Set property value")
        builder.appendLine("---@param Object UObject")
        builder.appendLine("---@param PropertyName string")
        builder.appendLine("---@param Value any")
        builder.appendLine("function UE.SetProperty(Object, PropertyName, Value) end")
        builder.appendLine()
        
        builder.appendLine("---Call function on object")
        builder.appendLine("---@param Object UObject")
        builder.appendLine("---@param FunctionName string")
        builder.appendLine("---@param ... any")
        builder.appendLine("---@return any")
        builder.appendLine("function UE.CallFunction(Object, FunctionName, ...) end")
        builder.appendLine()
        
        // Logging functions
        builder.appendLine("---Print a message to the log")
        builder.appendLine("---@param Message string")
        builder.appendLine("function UE.Log(Message) end")
        builder.appendLine()
        
        builder.appendLine("---Print a warning message to the log")
        builder.appendLine("---@param Message string")
        builder.appendLine("function UE.LogWarning(Message) end")
        builder.appendLine()
        
        builder.appendLine("---Print an error message to the log")
        builder.appendLine("---@param Message string")
        builder.appendLine("function UE.LogError(Message) end")
        builder.appendLine()
        
        // Utility functions
        builder.appendLine("---Check if object is valid")
        builder.appendLine("---@param Object UObject?")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UE.IsValid(Object) end")
        builder.appendLine()
        
        builder.appendLine("---Get object name")
        builder.appendLine("---@param Object UObject")
        builder.appendLine("---@return string")
        builder.appendLine("function UE.GetName(Object) end")
        builder.appendLine()
        
        builder.appendLine("---Get object class")
        builder.appendLine("---@param Object UObject")
        builder.appendLine("---@return UClass")
        builder.appendLine("function UE.GetObjectClass(Object) end")
        builder.appendLine()
        
        builder.appendLine("---Check if object is of class")
        builder.appendLine("---@param Object UObject")
        builder.appendLine("---@param Class UClass")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UE.IsA(Object, Class) end")
        builder.appendLine()
        
        // Delegate functions
        builder.appendLine("---Bind delegate")
        builder.appendLine("---@param Delegate Delegate")
        builder.appendLine("---@param Object UObject")
        builder.appendLine("---@param FunctionName string")
        builder.appendLine("function UE.BindDelegate(Delegate, Object, FunctionName) end")
        builder.appendLine()
        
        builder.appendLine("---Unbind delegate")
        builder.appendLine("---@param Delegate Delegate")
        builder.appendLine("---@param Object UObject")
        builder.appendLine("---@param FunctionName string")
        builder.appendLine("function UE.UnbindDelegate(Delegate, Object, FunctionName) end")
        builder.appendLine()
        
        builder.appendLine("---Execute delegate")
        builder.appendLine("---@param Delegate Delegate")
        builder.appendLine("---@param ... any")
        builder.appendLine("function UE.ExecuteDelegate(Delegate, ...) end")
        builder.appendLine()
    }
    
    /**
     * Add UnLua interface types
     */
    private fun addUnLuaInterface(builder: StringBuilder) {
        builder.appendLine("---@class UUnLuaInterface")
        builder.appendLine("local UUnLuaInterface = {}")
        builder.appendLine()
    }
    
    /**
     * Add UnLua specific functions
     */
    private fun addUnLuaFunctions(builder: StringBuilder) {
        builder.appendLine("-- UnLua binding functions")
        
        // Core binding functions
        builder.appendLine("---Bind a Lua table to a UObject")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@param luaTable table")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UnLua.Bind(object, luaTable) end")
        builder.appendLine()
        
        builder.appendLine("---Unbind a Lua table from a UObject")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UnLua.Unbind(object) end")
        builder.appendLine()
        
        builder.appendLine("---Check if a UObject is bound to Lua")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UnLua.IsBound(object) end")
        builder.appendLine()
        
        // Override functions
        builder.appendLine("---Override a UFunction with Lua implementation")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@param functionName string")
        builder.appendLine("---@param luaFunction function")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UnLua.Override(object, functionName, luaFunction) end")
        builder.appendLine()
        
        builder.appendLine("---Remove function override")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@param functionName string")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UnLua.RemoveOverride(object, functionName) end")
        builder.appendLine()
        
        builder.appendLine("---Check if a function is overridden")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@param functionName string")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UnLua.IsOverridden(object, functionName) end")
        builder.appendLine()
        
        // Table access functions
        builder.appendLine("---Get the Lua table bound to a UObject")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@return table?")
        builder.appendLine("function UnLua.GetLuaTable(object) end")
        builder.appendLine()
        
        builder.appendLine("---Set the Lua table for a UObject")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@param luaTable table")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UnLua.SetLuaTable(object, luaTable) end")
        builder.appendLine()
        
        // Original function calls
        builder.appendLine("---Call the original implementation of an overridden function")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@param functionName string")
        builder.appendLine("---@param ... any")
        builder.appendLine("---@return any")
        builder.appendLine("function UnLua.CallOriginal(object, functionName, ...) end")
        builder.appendLine()
        
        builder.appendLine("---Call the super implementation of a function")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@param functionName string")
        builder.appendLine("---@param ... any")
        builder.appendLine("---@return any")
        builder.appendLine("function UnLua.CallSuper(object, functionName, ...) end")
        builder.appendLine()
        
        // Module management
        builder.appendLine("---Require a Lua module")
        builder.appendLine("---@param moduleName string")
        builder.appendLine("---@return table")
        builder.appendLine("function UnLua.Require(moduleName) end")
        builder.appendLine()
        
        builder.appendLine("---Reload a Lua module")
        builder.appendLine("---@param moduleName string")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UnLua.ReloadModule(moduleName) end")
        builder.appendLine()
        
        builder.appendLine("---Get loaded modules")
        builder.appendLine("---@return table")
        builder.appendLine("function UnLua.GetLoadedModules() end")
        builder.appendLine()
        
        // Reflection helpers
        builder.appendLine("---Get UFunction by name")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@param functionName string")
        builder.appendLine("---@return UFunction?")
        builder.appendLine("function UnLua.GetUFunction(object, functionName) end")
        builder.appendLine()
        
        builder.appendLine("---Get UProperty by name")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@param propertyName string")
        builder.appendLine("---@return UProperty?")
        builder.appendLine("function UnLua.GetUProperty(object, propertyName) end")
        builder.appendLine()
        
        builder.appendLine("---Get all UFunctions of an object")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@return UFunction[]")
        builder.appendLine("function UnLua.GetAllUFunctions(object) end")
        builder.appendLine()
        
        builder.appendLine("---Get all UProperties of an object")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@return UProperty[]")
        builder.appendLine("function UnLua.GetAllUProperties(object) end")
        builder.appendLine()
        
        // Delegate binding
        builder.appendLine("---Bind a multicast delegate")
        builder.appendLine("---@param delegate MulticastDelegate")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@param functionName string")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UnLua.BindMulticastDelegate(delegate, object, functionName) end")
        builder.appendLine()
        
        builder.appendLine("---Unbind a multicast delegate")
        builder.appendLine("---@param delegate MulticastDelegate")
        builder.appendLine("---@param object UObject")
        builder.appendLine("---@param functionName string")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UnLua.UnbindMulticastDelegate(delegate, object, functionName) end")
        builder.appendLine()
        
        builder.appendLine("---Clear all bindings from a multicast delegate")
        builder.appendLine("---@param delegate MulticastDelegate")
        builder.appendLine("function UnLua.ClearMulticastDelegate(delegate) end")
        builder.appendLine()
        
        // Utility functions
        builder.appendLine("---Get UnLua version")
        builder.appendLine("---@return string")
        builder.appendLine("function UnLua.GetVersion() end")
        builder.appendLine()
        
        builder.appendLine("---Enable/disable hot reload")
        builder.appendLine("---@param enabled boolean")
        builder.appendLine("function UnLua.SetHotReloadEnabled(enabled) end")
        builder.appendLine()
        
        builder.appendLine("---Check if hot reload is enabled")
        builder.appendLine("---@return boolean")
        builder.appendLine("function UnLua.IsHotReloadEnabled() end")
        builder.appendLine()
        
        builder.appendLine("---Get Lua state")
        builder.appendLine("---@return userdata")
        builder.appendLine("function UnLua.GetLuaState() end")
        builder.appendLine()
        
        // Error handling
        builder.appendLine("---Set error handler")
        builder.appendLine("---@param handler function")
        builder.appendLine("function UnLua.SetErrorHandler(handler) end")
        builder.appendLine()
        
        builder.appendLine("---Get last error")
        builder.appendLine("---@return string?")
        builder.appendLine("function UnLua.GetLastError() end")
        builder.appendLine()
    }
    
    /**
     * Export engine reflection types (equivalent to UnLua's CollectTypes)
     * This scans for UClass, UScriptStruct, UEnum types that support reflection
     */
    /**
     * Export statically exported types (reflected and non-reflected classes, enums, functions)
     * Based on UnLuaIntelliSenseCommandlet::Main
     */
    private fun exportStaticallyExportedTypes(builder: StringBuilder) {
        builder.appendLine("-- Statically Exported Types (from UnLua bindings)")
        builder.appendLine("-- Based on UnLuaIntelliSenseCommandlet workflow")
        builder.appendLine()
        
        // Class blacklist from commandlet
        val classBlackList = setOf(
            "int8", "int16", "int32", "int64",
            "uint8", "uint16", "uint32", "uint64", 
            "float", "double", "bool",
            "FName", "FString", "FText"
        )
        
        // Function blacklist from commandlet
        val funcBlackList = setOf("OnModuleHotfixed")
        
        // Export common reflected classes that UnLua typically exports
        exportCommonReflectedClasses(builder, classBlackList)
        
        // Export common non-reflected classes
        exportCommonNonReflectedClasses(builder, classBlackList)
        
        // Export common enums
        exportCommonEnums(builder)
        
        // Export global functions (excluding blacklisted ones)
        exportGlobalFunctions(builder, funcBlackList)
    }

    private fun exportEngineReflectionTypes(builder: StringBuilder, ueProjectPath: String?) {
        builder.appendLine("-- Engine Reflection Types (UClass, UStruct, UEnum)")
        builder.appendLine("-- Generated based on UnLua's reflection scanning")
        builder.appendLine("-- Simulates TObjectIterator<UClass>, TObjectIterator<UScriptStruct>, TObjectIterator<UEnum>")
        builder.appendLine()
        
        // 1. Export statically exported classes (like UnLuaIntelliSenseCommandlet does)
        exportStaticallyExportedTypes(builder)
        
        // 2. Export core engine types first
        exportCoreEngineTypes(builder)
        
        // 3. Export engine module types
        exportEngineModuleTypes(builder)
        
        // 4. If UE project path is provided, export project-specific types
        if (!ueProjectPath.isNullOrEmpty()) {
            exportProjectTypes(builder, ueProjectPath)
        }
        
        // 5. Export plugin types
        exportPluginTypes(builder, ueProjectPath)
    }
    
    /**
     * Export core engine types (UObject hierarchy, basic types)
     */
    private fun exportCoreEngineTypes(builder: StringBuilder) {
        builder.appendLine("-- Core Engine Types (UObject hierarchy)")
        
        // Base UObject types
        exportUObjectHierarchy(builder)
        
        // Math types
        exportMathTypes(builder)
        
        // Container types
        exportContainerTypes(builder)
        
        // Core gameplay types
        exportGameplayTypes(builder)
    }
    
    /**
     * Export engine module types (from Engine, CoreUObject, etc.)
     */
    private fun exportEngineModuleTypes(builder: StringBuilder) {
        builder.appendLine("-- Engine Module Types")
        
        // This would scan engine directories for reflection types
        // For now, we'll include commonly used engine types
        exportCommonEngineTypes(builder)
    }
    
    /**
     * Export project-specific types (equivalent to scanning project blueprints and C++ classes)
     */
    private fun exportProjectTypes(builder: StringBuilder, ueProjectPath: String) {
        try {
            val projectFile = File(ueProjectPath)
            if (!projectFile.exists()) {
                builder.appendLine("-- Project path not found: $ueProjectPath")
                return
            }
            
            builder.appendLine("-- Project-specific types from: ${projectFile.name}")
            builder.appendLine()
            
            val projectDir = if (projectFile.name.endsWith(".uproject")) {
                projectFile.parentFile
            } else {
                projectFile
            }
            
            // Scan project C++ classes with reflection
            scanProjectCppClasses(builder, projectDir)
            
            // Scan project blueprints
            scanProjectBlueprints(builder, projectDir)
            
        } catch (e: Exception) {
            builder.appendLine("-- Error scanning project types: ${e.message}")
        }
    }
    
    /**
     * Export plugin types (scan enabled plugins for reflection types)
     */
    private fun exportPluginTypes(builder: StringBuilder, ueProjectPath: String?) {
        if (ueProjectPath.isNullOrEmpty()) return
        
        try {
            val projectFile = File(ueProjectPath)
            val projectDir = if (projectFile.name.endsWith(".uproject")) {
                projectFile.parentFile
            } else {
                projectFile
            }
            val pluginsDir = File(projectDir, "Plugins")
            
            if (pluginsDir.exists() && pluginsDir.isDirectory) {
                builder.appendLine("-- Plugin Types:")
                scanPluginTypes(builder, pluginsDir)
            }
        } catch (e: Exception) {
            builder.appendLine("-- Error scanning plugin types: ${e.message}")
        }
    }
    
    /**
     * Export UObject hierarchy types
     */
    private fun exportUObjectHierarchy(builder: StringBuilder) {
        addCoreUETypes(builder)
    }
    
    /**
     * Export math types
     */
    private fun exportMathTypes(builder: StringBuilder) {
        addMathTypes(builder)
    }
    
    /**
     * Export container types
     */
    private fun exportContainerTypes(builder: StringBuilder) {
        addContainerTypes(builder)
    }
    
    /**
     * Export core gameplay types
     */
    private fun exportGameplayTypes(builder: StringBuilder) {
        // Additional gameplay framework types can be added here
    }
    
    /**
     * Export common engine types
     */
    private fun exportCommonEngineTypes(builder: StringBuilder) {
        // Additional engine types can be added here
    }
    
    /**
     * Scan project C++ classes with reflection markup
     */
    private fun scanProjectCppClasses(builder: StringBuilder, projectDir: File) {
        val sourceDir = File(projectDir, "Source")
        if (sourceDir.exists() && sourceDir.isDirectory) {
            parseCppHeaders(builder, sourceDir)
        }
    }
    
    /**
     * Scan project blueprints
     */
    private fun scanProjectBlueprints(builder: StringBuilder, projectDir: File) {
        val contentDir = File(projectDir, "Content")
        if (contentDir.exists() && contentDir.isDirectory) {
            parseBlueprintAssets(builder, contentDir)
        }
    }
    
    /**
      * Scan plugin types
      */
     private fun scanPluginTypes(builder: StringBuilder, pluginsDir: File) {
         pluginsDir.listFiles()?.forEach { pluginDir ->
             if (pluginDir.isDirectory) {
                 val pluginSourceDir = File(pluginDir, "Source")
                 if (pluginSourceDir.exists()) {
                     parseCppHeaders(builder, pluginSourceDir)
                 }
             }
         }
     }
     
     /**
      * Export UE utility functions
      */
     private fun exportUEUtilityFunctions(builder: StringBuilder) {
         addUEFunctions(builder)
     }
     
     /**
      * Export common reflected classes that UnLua typically exports
      * Based on UnLua's ExportedReflectedClasses
      */
     private fun exportCommonReflectedClasses(builder: StringBuilder, classBlackList: Set<String>) {
         builder.appendLine("-- Common Reflected Classes (from UnLua bindings)")
         
         val commonReflectedClasses = listOf(
             // Core UObject types
             "UObject", "UClass", "UStruct", "UFunction", "UProperty",
             // Actor hierarchy
             "AActor", "APawn", "ACharacter", "APlayerController", "AGameModeBase",
             // Component types
             "UActorComponent", "USceneComponent", "UPrimitiveComponent", "UStaticMeshComponent",
             // Asset types
             "UBlueprint", "UStaticMesh", "UMaterial", "UTexture", "UTexture2D",
             // Animation types
             "UAnimationAsset", "UAnimSequence", "UAnimBlueprint", "USkeletalMesh",
             // Audio types
             "USoundBase", "USoundWave", "USoundCue",
             // UI types
             "UWidget", "UUserWidget", "UButton", "UTextBlock", "UImage"
         )
         
         commonReflectedClasses.forEach { className ->
             if (!classBlackList.contains(className)) {
                 builder.appendLine("---@class $className")
                 builder.appendLine("local $className = {}")
                 builder.appendLine()
             }
         }
     }
     
     /**
      * Export common non-reflected classes that UnLua exports
      * Based on UnLua's ExportedNonReflectedClasses
      */
     private fun exportCommonNonReflectedClasses(builder: StringBuilder, classBlackList: Set<String>) {
         builder.appendLine("-- Common Non-Reflected Classes (from UnLua bindings)")
         
         val commonNonReflectedClasses = listOf(
             // Math types
             "FVector", "FVector2D", "FVector4", "FRotator", "FQuat", "FTransform",
             "FMatrix", "FPlane", "FColor", "FLinearColor",
             // Container types
             "TArray", "TMap", "TSet",
             // String types
             "FString", "FName", "FText",
             // Delegate types
             "FDelegate", "FMulticastDelegate"
         )
         
         commonNonReflectedClasses.forEach { className ->
             if (!classBlackList.contains(className)) {
                 builder.appendLine("---@class $className")
                 builder.appendLine("local $className = {}")
                 builder.appendLine()
             }
         }
     }
     
     /**
      * Export common enums that UnLua typically exports
      */
     private fun exportCommonEnums(builder: StringBuilder) {
         builder.appendLine("-- Common Enums (from UnLua bindings)")
         
         val commonEnums = listOf(
             "ECollisionEnabled", "ECollisionResponse", "ECollisionChannel",
             "EMovementMode", "ENetMode", "ENetRole",
             "EInputEvent", "EKeys", "EControllerHand",
             "EBlendMode", "EMaterialDomain", "EPixelFormat",
             "EAnimationMode", "EBoneSpaces", "EAnimInterpolationType"
         )
         
         commonEnums.forEach { enumName ->
             builder.appendLine("---@enum $enumName")
             builder.appendLine("local $enumName = {}")
             builder.appendLine()
         }
     }
     
     /**
      * Export global functions (excluding blacklisted ones)
      * Based on UnLua's ExportedFunctions
      */
     private fun exportGlobalFunctions(builder: StringBuilder, funcBlackList: Set<String>) {
         builder.appendLine("-- Global Functions (from UnLua bindings)")
         
         val globalFunctions = listOf(
             // Logging functions
             Pair("print", listOf(Pair("message", "string"))),
             Pair("UE.UEPrint", listOf(Pair("message", "string"))),
             Pair("UE.UELog", listOf(Pair("message", "string"))),
             
             // Utility functions
             Pair("UE.LoadObject", listOf(Pair("path", "string"))),
             Pair("UE.LoadClass", listOf(Pair("path", "string"))),
             Pair("UE.NewObject", listOf(Pair("class", "UClass"), Pair("outer", "UObject"))),
             
             // Math functions
             Pair("UE.FMath.Sin", listOf(Pair("value", "number"))),
             Pair("UE.FMath.Cos", listOf(Pair("value", "number"))),
             Pair("UE.FMath.Sqrt", listOf(Pair("value", "number"))),
             
             // String functions
             Pair("UE.FString.Printf", listOf(Pair("format", "string"))),
             
             // Array functions
             Pair("UE.TArray.Add", listOf(Pair("item", "any"))),
             Pair("UE.TArray.Remove", listOf(Pair("index", "integer")))
         )
         
         globalFunctions.forEach { (funcName, params) ->
             if (!funcBlackList.contains(funcName)) {
                 // Generate parameter annotations
                 params.forEach { (paramName, paramType) ->
                     builder.appendLine("---@param $paramName $paramType")
                 }
                 builder.appendLine("function $funcName() end")
                 builder.appendLine()
             }
         }
     }
    
    /**
     * Parse .uproject file for module information
     */
    private fun parseUProjectFile(builder: StringBuilder, uprojectFile: File) {
        try {
            val content = uprojectFile.readText()
            builder.appendLine("-- Modules from ${uprojectFile.name}:")
            
            // Simple regex to find module names (this could be improved with proper JSON parsing)
            val modulePattern = Regex("\"Name\"\\s*:\\s*\"([^\"]+)\"")
            val matches = modulePattern.findAll(content)
            
            for (match in matches) {
                val moduleName = match.groupValues[1]
                if (moduleName.isNotEmpty() && !moduleName.equals("Engine", ignoreCase = true)) {
                    builder.appendLine("---@class ${moduleName}Module")
                    builder.appendLine("local ${moduleName}Module = {}")
                    builder.appendLine()
                }
            }
        } catch (e: Exception) {
            builder.appendLine("-- Error parsing .uproject file: ${e.message}")
        }
    }
    
    /**
     * Parse Blueprint assets for class information
     */
    private fun parseBlueprintAssets(builder: StringBuilder, contentDir: File) {
        try {
            builder.appendLine("-- Blueprint Classes from Content directory:")
            
            contentDir.walkTopDown()
                .filter { it.isFile && it.extension == "uasset" }
                .take(100) // Limit to avoid performance issues
                .forEach { assetFile ->
                    val assetName = assetFile.nameWithoutExtension
                    if (assetName.startsWith("BP_") || assetName.contains("Blueprint")) {
                        // Generate a basic class definition for Blueprint assets
                        val className = if (assetName.startsWith("BP_")) {
                            "A${assetName.substring(3)}" // Convert BP_Something to ASomething
                        } else {
                            "A$assetName"
                        }
                        
                        builder.appendLine("---@class $className : AActor")
                        builder.appendLine("local $className = {}")
                        builder.appendLine()
                    }
                }
        } catch (e: Exception) {
            builder.appendLine("-- Error parsing Blueprint assets: ${e.message}")
        }
    }
    
    /**
     * Parse C++ headers for UCLASS, USTRUCT, UENUM declarations
     */
    private fun parseCppHeaders(builder: StringBuilder, sourceDir: File) {
        try {
            builder.appendLine("-- C++ Classes from Source directory:")
            
            sourceDir.walkTopDown()
                .filter { it.isFile && (it.extension == "h" || it.extension == "hpp") }
                .take(50) // Limit to avoid performance issues
                .forEach { headerFile ->
                    parseHeaderFile(builder, headerFile)
                }
        } catch (e: Exception) {
            builder.appendLine("-- Error parsing C++ headers: ${e.message}")
        }
    }
    
    /**
     * Parse individual header file for UE declarations
     * Enhanced to extract properties and functions like UnLua does
     */
    private fun parseHeaderFile(builder: StringBuilder, headerFile: File) {
        try {
            val content = headerFile.readText()
            
            // Parse UCLASS declarations with inheritance
            parseUClassDeclarations(builder, content)
            
            // Parse USTRUCT declarations
            parseUStructDeclarations(builder, content)
            
            // Parse UENUM declarations
            parseUEnumDeclarations(builder, content)
            
        } catch (e: Exception) {
            // Silently ignore individual file parsing errors
        }
    }
    
    /**
     * Parse UCLASS declarations with properties and functions
     */
    private fun parseUClassDeclarations(builder: StringBuilder, content: String) {
        // Enhanced pattern to capture inheritance
        val uclassPattern = Regex("UCLASS\\([^)]*\\)\\s*class\\s+[A-Z_]*\\s+([A-Za-z_][A-Za-z0-9_]*)(?:\\s*:\\s*public\\s+([A-Za-z_][A-Za-z0-9_]*))?")
        val uclassMatches = uclassPattern.findAll(content)
        
        for (match in uclassMatches) {
            val className = match.groupValues[1]
            val baseClass = match.groupValues.getOrNull(2) ?: "UObject"
            
            if (className.isNotEmpty()) {
                builder.appendLine("---@class $className : $baseClass")
                
                // Try to extract class body and parse properties/functions
                val classBodyPattern = Regex("class\\s+[A-Z_]*\\s+$className[^{]*\\{([^}]*)\\}", RegexOption.DOT_MATCHES_ALL)
                val classBodyMatch = classBodyPattern.find(content)
                
                if (classBodyMatch != null) {
                    val classBody = classBodyMatch.groupValues[1]
                    parseClassProperties(builder, classBody)
                }
                
                builder.appendLine("local $className = {}")
                builder.appendLine()
            }
        }
    }
    
    /**
     * Parse USTRUCT declarations
     */
    private fun parseUStructDeclarations(builder: StringBuilder, content: String) {
        val ustructPattern = Regex("USTRUCT\\([^)]*\\)\\s*struct\\s+[A-Z_]*\\s+([A-Za-z_][A-Za-z0-9_]*)")
        val ustructMatches = ustructPattern.findAll(content)
        
        for (match in ustructMatches) {
            val structName = match.groupValues[1]
            if (structName.isNotEmpty()) {
                builder.appendLine("---@class $structName")
                
                // Try to extract struct body and parse properties
                val structBodyPattern = Regex("struct\\s+[A-Z_]*\\s+$structName[^{]*\\{([^}]*)\\}", RegexOption.DOT_MATCHES_ALL)
                val structBodyMatch = structBodyPattern.find(content)
                
                if (structBodyMatch != null) {
                    val structBody = structBodyMatch.groupValues[1]
                    parseClassProperties(builder, structBody)
                }
                
                builder.appendLine("local $structName = {}")
                builder.appendLine()
            }
        }
    }
    
    /**
     * Parse UENUM declarations with enum values
     */
    private fun parseUEnumDeclarations(builder: StringBuilder, content: String) {
        val uenumPattern = Regex("UENUM\\([^)]*\\)\\s*enum\\s+(?:class\\s+)?([A-Za-z_][A-Za-z0-9_]*)")
        val uenumMatches = uenumPattern.findAll(content)
        
        for (match in uenumMatches) {
            val enumName = match.groupValues[1]
            if (enumName.isNotEmpty()) {
                builder.appendLine("---@enum $enumName")
                
                // Try to extract enum values
                val enumBodyPattern = Regex("enum\\s+(?:class\\s+)?$enumName[^{]*\\{([^}]*)\\}", RegexOption.DOT_MATCHES_ALL)
                val enumBodyMatch = enumBodyPattern.find(content)
                
                if (enumBodyMatch != null) {
                    val enumBody = enumBodyMatch.groupValues[1]
                    parseEnumValues(builder, enumBody)
                }
                
                builder.appendLine("local $enumName = {}")
                builder.appendLine()
            }
        }
    }
    
    /**
     * Parse class properties marked with UPROPERTY
     */
    private fun parseClassProperties(builder: StringBuilder, classBody: String) {
        val upropertyPattern = Regex("UPROPERTY\\([^)]*\\)\\s*([A-Za-z_][A-Za-z0-9_<>*&\\s]*?)\\s+([A-Za-z_][A-Za-z0-9_]*);")
        val upropertyMatches = upropertyPattern.findAll(classBody)
        
        for (match in upropertyMatches) {
            val propertyType = match.groupValues[1].trim()
            val propertyName = match.groupValues[2].trim()
            
            if (propertyName.isNotEmpty()) {
                val luaType = mapCppTypeToLua(propertyType)
                builder.appendLine("---@field public $propertyName $luaType")
            }
        }
    }
    
    /**
     * Parse enum values
     */
    private fun parseEnumValues(builder: StringBuilder, enumBody: String) {
        val enumValuePattern = Regex("([A-Za-z_][A-Za-z0-9_]*)(?:\\s*=\\s*[^,}]*)?")
        val enumValueMatches = enumValuePattern.findAll(enumBody)
        
        for (match in enumValueMatches) {
            val enumValue = match.groupValues[1].trim()
            if (enumValue.isNotEmpty() && !enumValue.contains("//") && !enumValue.contains("/*")) {
                builder.appendLine("---@field public $enumValue integer")
            }
        }
    }
    
    /**
     * Map C++ types to Lua types
     */
    private fun mapCppTypeToLua(cppType: String): String {
        val cleanType = cppType.replace("*", "").replace("&", "").trim()
        
        return when {
            cleanType.startsWith("TArray<") -> "table"
            cleanType.startsWith("TMap<") -> "table"
            cleanType.startsWith("TSet<") -> "table"
            cleanType in TYPE_MAPPINGS -> TYPE_MAPPINGS[cleanType] ?: "any"
            cleanType.startsWith("F") || cleanType.startsWith("U") || cleanType.startsWith("A") -> cleanType
            cleanType == "int32" || cleanType == "int" -> "integer"
            cleanType == "float" || cleanType == "double" -> "number"
            cleanType == "bool" -> "boolean"
            cleanType == "FString" || cleanType == "FName" || cleanType == "FText" -> "string"
            else -> "any"
        }
    }
    
    /**
     * Add UELuaIntelliSense folder to project libraries
     * Note: This is a placeholder for future implementation
     */
    private fun addToProjectLibraries(project: Project, ueIntelliSenseFolder: File) {
        // TODO: Implement library addition functionality
        // For now, just ensure the folder exists and is accessible
        println("UELuaIntelliSense folder created at: ${ueIntelliSenseFolder.absolutePath}")
    }
    
    /**
     * Escape symbol names to avoid conflicts with Lua keywords
     * Based on UnLua's EscapeSymbolName function
     */
    private fun escapeSymbolName(name: String): String {
        var escapedName = name
        
        // Add prefix if it's a Lua keyword
        if (LUA_KEYWORDS.contains(escapedName.lowercase())) {
            escapedName = "_$escapedName"
        }
        
        // Replace special characters (except Chinese characters)
        escapedName = escapedName.map { char ->
            when {
                char.isLetterOrDigit() || char == '_' -> char
                char.code >= 0x100 -> char // Keep Chinese characters
                else -> '_'
            }
        }.joinToString("")
        
        // Add prefix if starts with digit
        if (escapedName.isNotEmpty() && escapedName[0].isDigit()) {
            escapedName = "_$escapedName"
        }
        
        return escapedName
    }
    
    /**
     * Escape comments to handle special characters
     * Based on UnLua's EscapeComments function
     */
    private fun escapeComments(comments: String, singleLine: Boolean = true): String {
        if (comments.isEmpty()) return comments
        
        val lines = comments.replace("@", "@@").split("\n")
        val filteredLines = lines.map { line ->
            if (line.startsWith("@")) "" else line
        }
        
        return if (singleLine) {
            filteredLines.joinToString(" ")
        } else {
            filteredLines.joinToString("\n---")
        }
    }
    
    /**
     * Get comment block for a field
     */
    private fun getCommentBlock(tooltip: String): String {
        if (tooltip.isEmpty()) return ""
        return "---${escapeComments(tooltip, false)}\n"
    }
    
    /**
     * Check if function name is valid for Lua
     */
    private fun isValidFunctionName(name: String): Boolean {
        if (name.isEmpty()) return false
        
        // Check for Lua keywords
        if (LUA_KEYWORDS.contains(name.lowercase())) return false
        
        // Check for invalid characters
        return name.all { char ->
            char.isLetterOrDigit() || char == '_'
        } && !name[0].isDigit()
    }
    
    /**
     * Generate property annotation based on UE property information
     */
    private fun generatePropertyAnnotation(propertyName: String, propertyType: String, 
                                         accessLevel: String = "public", tooltip: String = ""): String {
        val builder = StringBuilder()
        val mappedType = TYPE_MAPPINGS[propertyType] ?: propertyType
        
        builder.append("---@field $accessLevel $propertyName $mappedType")
        
        if (tooltip.isNotEmpty()) {
            builder.append(" @${escapeComments(tooltip, true)}")
        }
        
        return builder.toString()
    }
    
    /**
     * Generate function annotation with parameters and return type
     */
    private fun generateFunctionAnnotation(functionName: String, parameters: List<Pair<String, String>>, 
                                         returnType: String = "", tooltip: String = ""): String {
        val builder = StringBuilder()
        
        // Add comment block if tooltip exists
        if (tooltip.isNotEmpty()) {
            builder.appendLine(getCommentBlock(tooltip))
        }
        
        // Add parameter annotations
        parameters.forEach { (paramName, paramType) ->
            val mappedType = TYPE_MAPPINGS[paramType] ?: paramType
            builder.appendLine("---@param $paramName $mappedType")
        }
        
        // Add return type annotation
        if (returnType.isNotEmpty()) {
            val mappedReturnType = TYPE_MAPPINGS[returnType] ?: returnType
            builder.appendLine("---@return $mappedReturnType")
        }
        
        return builder.toString()
    }
}