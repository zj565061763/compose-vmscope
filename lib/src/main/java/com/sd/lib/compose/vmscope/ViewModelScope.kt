package com.sd.lib.compose.vmscope

import android.os.Looper
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 创建[ComposeVMScope]，如果调用此方法的地方在组合中被移除，[ComposeVMScope]会清空所有保存的[ViewModel]
 */
@Composable
fun rememberVMScope(): ComposeVMScope {
   val scope = remember { ComposeVMScopeImpl() }
   DisposableEffect(scope) {
      onDispose {
         scope.destroy()
      }
   }
   return scope
}

/**
 * 根据[key]获取[ViewModel]
 */
@Composable
inline fun <reified T : ViewModel> ComposeVMScope.get(key: String): T {
   return create(key, T::class.java) { params ->
      viewModel(
         modelClass = params.vmClass,
         viewModelStoreOwner = params.viewModelStoreOwner,
         key = params.key,
      )
   }
}

/**
 * 根据[key]获取[ViewModel]
 */
@Composable
inline fun <reified T : ViewModel> ComposeVMScope.get(
   key: String,
   noinline factory: (CreationExtras) -> T,
): T {
   val factoryUpdated by rememberUpdatedState(factory)

   val defaultFactory = remember {
      object : ViewModelProvider.Factory {
         override fun <T : ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return factoryUpdated(CreationExtras.Empty) as T
         }

         override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            @Suppress("UNCHECKED_CAST")
            return factoryUpdated(extras) as T
         }
      }
   }

   return create(key, T::class.java) { params ->
      viewModel(
         modelClass = params.vmClass,
         viewModelStoreOwner = params.viewModelStoreOwner,
         key = params.key,
         factory = defaultFactory,
      )
   }
}

interface ComposeVMScope {
   /**
    * 根据[key]获取[ViewModel]，每次调用此方法都会从[factory]中获取
    */
   @Composable
   fun <T : ViewModel> create(
      key: String,
      clazz: Class<T>,
      factory: @Composable (CreateVMParams<T>) -> T,
   ): T

   /**
    * 移除[key]对应的[ViewModel]
    */
   @MainThread
   fun remove(key: String)
}

/**
 * 创建[ViewModel]参数
 */
data class CreateVMParams<VM : ViewModel>(
   val vmClass: Class<VM>,
   val viewModelStoreOwner: ViewModelStoreOwner,
   val key: String,
)

private class ComposeVMScopeImpl : ComposeVMScope {
   private var _isDestroyed = false
   private val _keyedOwners = mutableMapOf<String, KeyedViewModelStoreOwner>()

   @Composable
   override fun <T : ViewModel> create(
      key: String,
      clazz: Class<T>,
      factory: @Composable (CreateVMParams<T>) -> T,
   ): T {
      if (_isDestroyed) error("Scope is destroyed.")

      val realOwner = _keyedOwners.getOrPut(key) { KeyedViewModelStoreOwner() }
      val localOwner = checkNotNull(LocalViewModelStoreOwner.current)

      val viewModelStoreOwner = remember(localOwner) {
         if (localOwner is HasDefaultViewModelProviderFactory) {
            ViewModelStoreOwnerHasDefault(
               owner = realOwner,
               factory = localOwner,
            )
         } else {
            realOwner
         }
      }

      val params = CreateVMParams(
         vmClass = clazz,
         viewModelStoreOwner = viewModelStoreOwner,
         key = key,
      )

      return factory(params).also { vm ->
         check(vm === realOwner.getViewModel(key)) {
            "ViewModel with key:$key was not found in KeyedViewModelStoreOwner, you should create ViewModel with CreateVMParams."
         }
      }
   }

   override fun remove(key: String) {
      _keyedOwners.remove(key)?.clear()
   }

   /**
    * 销毁所有[ViewModel]
    */
   fun destroy() {
      checkMainThread()
      _isDestroyed = true
      while (_keyedOwners.isNotEmpty()) {
         _keyedOwners.keys.toTypedArray().forEach {
            remove(it)
         }
      }
   }

   private class ViewModelStoreOwnerHasDefault(
      private val owner: ViewModelStoreOwner,
      private val factory: HasDefaultViewModelProviderFactory,
   ) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {

      override val viewModelStore: ViewModelStore
         get() = owner.viewModelStore

      override val defaultViewModelProviderFactory: ViewModelProvider.Factory
         get() = factory.defaultViewModelProviderFactory

      override val defaultViewModelCreationExtras: CreationExtras
         get() = factory.defaultViewModelCreationExtras
   }

   private class KeyedViewModelStoreOwner : ViewModelStoreOwner {
      override val viewModelStore = ViewModelStore()
      private val _vmMap = viewModelStore.vmMap()

      fun getViewModel(key: String): ViewModel? {
         return _vmMap[key]
      }

      fun clear() {
         viewModelStore.clear()
      }
   }
}

/**
 * 获取[ViewModelStore]内保存[ViewModel]的[Map]
 */
private fun ViewModelStore.vmMap(): MutableMap<String, ViewModel> {
   return ViewModelStore::class.java.run {
      try {
         getDeclaredField("map")
      } catch (e: Throwable) {
         null
      } ?: try {
         getDeclaredField("mMap")
      } catch (e: Throwable) {
         null
      } ?: error("map field was not found in ${ViewModelStore::class.java.name}")
   }.let { field ->
      field.isAccessible = true
      @Suppress("UNCHECKED_CAST")
      field.get(this@vmMap) as MutableMap<String, ViewModel>
   }
}

private fun checkMainThread() {
   check(Looper.myLooper() === Looper.getMainLooper()) {
      "Expected main thread but was " + Thread.currentThread().name
   }
}