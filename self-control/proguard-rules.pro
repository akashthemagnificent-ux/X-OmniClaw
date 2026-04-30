# Self-Control Module ProGuard Rules

# Keep all public Skill classes and interfaces
-keep public class com.shijing.xomniclaw.selfcontrol.** { *; }

# Keep all classes that implement Skill interface
-keep class * implements com.shijing.xomniclaw.agent.tools.Skill { *; }

# Keep SelfControlRegistry
-keep class com.shijing.xomniclaw.selfcontrol.SelfControlRegistry { *; }

# Keep all Skill execute methods (reflection may be used)
-keepclassmembers class * implements com.shijing.xomniclaw.agent.tools.Skill {
    public *** execute(...);
}

# Keep SkillResult
-keep class com.shijing.xomniclaw.agent.tools.SkillResult { *; }

# Keep tool definition classes
-keep class com.shijing.xomniclaw.providers.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
