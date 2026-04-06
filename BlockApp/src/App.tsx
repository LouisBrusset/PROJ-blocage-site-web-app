import React from 'react';
import { NavigationContainer } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { Text } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';

import HomeScreen from './screens/HomeScreen';
import AddUrlScreen from './screens/AddUrlScreen';
import GroupsScreen from './screens/GroupsScreen';
import SettingsScreen from './screens/SettingsScreen';

const Tab = createBottomTabNavigator();
const Stack = createNativeStackNavigator();

function UrlsStack() {
  return (
    <Stack.Navigator
      screenOptions={{
        headerStyle: { backgroundColor: '#5C6BC0' },
        headerTintColor: '#fff',
        headerTitleStyle: { fontWeight: '700' },
      }}
    >
      <Stack.Screen
        name="UrlsList"
        component={HomeScreen}
        options={{ title: 'Sites bloqués' }}
      />
      <Stack.Screen
        name="AddUrl"
        component={AddUrlScreen}
        options={({ route }: any) => ({
          title: route.params?.item ? 'Modifier le site' : 'Ajouter un site',
        })}
      />
    </Stack.Navigator>
  );
}

export default function App() {
  return (
    <SafeAreaProvider>
      <NavigationContainer>
        <Tab.Navigator
          screenOptions={({ route }) => ({
            tabBarIcon: ({ focused }) => {
              const icons: Record<string, string> = {
                Urls: '🚫',
                Groupes: '📁',
                Parametres: '⚙️',
              };
              return <Text style={{ fontSize: focused ? 22 : 18 }}>{icons[route.name]}</Text>;
            },
            tabBarActiveTintColor: '#5C6BC0',
            tabBarInactiveTintColor: '#aaa',
            tabBarStyle: { height: 64, paddingBottom: 8 },
            headerShown: false,
          })}
        >
          <Tab.Screen name="Urls" component={UrlsStack} options={{ title: 'Sites' }} />
          <Tab.Screen name="Groupes" component={GroupsScreen}
            options={{
              headerShown: true,
              title: 'Groupes',
              headerStyle: { backgroundColor: '#5C6BC0' },
              headerTintColor: '#fff',
              headerTitleStyle: { fontWeight: '700' },
            }}
          />
          <Tab.Screen name="Parametres" component={SettingsScreen}
            options={{
              headerShown: true,
              title: 'Paramètres',
              headerStyle: { backgroundColor: '#5C6BC0' },
              headerTintColor: '#fff',
              headerTitleStyle: { fontWeight: '700' },
            }}
          />
        </Tab.Navigator>
      </NavigationContainer>
    </SafeAreaProvider>
  );
}
